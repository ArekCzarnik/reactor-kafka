/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package reactor.kafka.inbound.internals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.RetriableCommitFailedException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.Cancellation;
import reactor.core.publisher.BlockingSink;
import reactor.core.publisher.BlockingSink.Emission;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.inbound.AckMode;
import reactor.kafka.inbound.KafkaInbound;
import reactor.kafka.inbound.InboundRecord;
import reactor.kafka.inbound.Offset;
import reactor.kafka.inbound.InboundOptions;
import reactor.kafka.inbound.Partition;
import reactor.kafka.inbound.internals.CommittableBatch.CommitArgs;

public class KafkaReceiver<K, V> implements KafkaInbound<K, V>, ConsumerRebalanceListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaReceiver.class.getName());

    private final InboundOptions<K, V> receiverOptions;
    private final List<Flux<? extends Event<?>>> fluxList = new ArrayList<>();
    private final List<Cancellation> cancellations = new ArrayList<>();
    private final AtomicLong requestsPending = new AtomicLong();
    private final AtomicBoolean needsHeartbeat = new AtomicBoolean();
    private final AtomicInteger consecutiveCommitFailures = new AtomicInteger();
    private final Scheduler eventScheduler;
    private final AtomicBoolean isActive = new AtomicBoolean();
    private final AtomicBoolean isClosed = new AtomicBoolean();
    private EmitterProcessor<Event<?>> eventEmitter;
    private BlockingSink<Event<?>> eventSubmission;
    private EmitterProcessor<ConsumerRecords<K, V>> recordEmitter;
    private BlockingSink<ConsumerRecords<K, V>> recordSubmission;
    private InitEvent initEvent;
    private PollEvent pollEvent;
    private HeartbeatEvent heartbeatEvent;
    private CommitEvent commitEvent;
    private Flux<Event<?>> eventFlux;
    private Flux<InboundRecord<K, V>> consumerFlux;
    private KafkaConsumer<K, V> consumer;

    public enum EventType {
        INIT, POLL, HEARTBEAT, COMMIT, CLOSE
    }

    public KafkaReceiver(InboundOptions<K, V> receiverOptions) {
        this.receiverOptions = receiverOptions.toImmutable();
        this.eventScheduler = Schedulers.newSingle("reactive-kafka-" + receiverOptions.groupId());
    }

    @Override
    public Flux<InboundRecord<K, V>> receive() {
        return createConsumerFlux((flux) -> receiverOptions.subscriber(this).accept(consumer));
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        log.debug("onPartitionsAssigned {}", partitions);
        // onAssign methods may perform seek. It is safe to use the consumer here since we are in a poll()
        if (partitions.size() > 0) {
            for (Consumer<Collection<Partition>> onAssign : receiverOptions.assignListeners())
                onAssign.accept(toSeekable(partitions));
        }
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        log.debug("onPartitionsRevoked {}", partitions);
        if (partitions.size() > 0) {
            // It is safe to use the consumer here since we are in a poll()
            commitEvent.runIfRequired(true);
            for (Consumer<Collection<Partition>> onRevoke : receiverOptions.revokeListeners()) {
                onRevoke.accept(toSeekable(partitions));
            }
        }
    }

    private Flux<InboundRecord<K, V>> createConsumerFlux(Consumer<Flux<InboundRecord<K, V>>> kafkaSubscribeOrAssign) {
        if (consumerFlux != null)
            throw new IllegalStateException("Multiple subscribers are not supported for KafkaFlux");

        initEvent = new InitEvent(kafkaSubscribeOrAssign);
        pollEvent = new PollEvent();
        heartbeatEvent = new HeartbeatEvent();
        commitEvent = new CommitEvent();

        recordEmitter = EmitterProcessor.create();
        recordSubmission = recordEmitter.connectSink();

        consumerFlux = recordEmitter
                .publishOn(Schedulers.parallel())
                .doOnSubscribe(s -> {
                        try {
                            start();
                        } catch (Exception e) {
                            log.error("Subscription to event flux failed", e);
                            throw e;
                        }
                    })
                .doOnCancel(() -> cancel())
                .concatMap(consumerRecords -> Flux.fromIterable(consumerRecords)
                                                  .map(record -> newConsumerMessage(record)));
        consumerFlux = consumerFlux
                .doOnRequest(r -> {
                        if (requestsPending.addAndGet(r) > 0)
                             pollEvent.scheduleIfRequired();
                    });

        return consumerFlux;
    }

    public KafkaConsumer<K, V> kafkaConsumer() {
        return consumer;
    }

    public CommittableBatch committableBatch() {
        return commitEvent.commitBatch;
    }

    public void close() {
        cancel();
    }

    private Collection<Partition> toSeekable(Collection<TopicPartition> partitions) {
        List<Partition> seekableList = new ArrayList<>(partitions.size());
        for (TopicPartition partition : partitions)
            seekableList.add(new ReceiverPartition(consumer, partition));
        return seekableList;
    }

    private void start() {
        log.debug("start");
        if (!isActive.compareAndSet(false, true))
            throw new IllegalStateException("Multiple subscribers are not supported for KafkaFlux");

        eventEmitter = EmitterProcessor.create();
        eventSubmission = eventEmitter.connectSink();
        eventScheduler.start();

        Flux<InitEvent> initFlux = Flux.just(initEvent);
        Flux<HeartbeatEvent> heartbeatFlux =
                Flux.interval(receiverOptions.heartbeatInterval())
                     .doOnSubscribe(i -> needsHeartbeat.set(true))
                     .map(i -> heartbeatEvent);

        fluxList.add(eventEmitter);
        fluxList.add(initFlux);
        fluxList.add(heartbeatFlux);
        AckMode ackMode = receiverOptions.ackMode();
        if ((ackMode == AckMode.AUTO_ACK || ackMode == AckMode.MANUAL_ACK) && receiverOptions.commitInterval() != null) {
            Flux<CommitEvent> periodicCommitFlux = Flux.interval(receiverOptions.commitInterval())
                             .map(i -> commitEvent);
            fluxList.add(periodicCommitFlux);
        }

        eventFlux = Flux.merge(fluxList)
                        .publishOn(eventScheduler);

        cancellations.add(eventFlux.subscribe(event -> doEvent(event)));
    }

    private void fail(Throwable e) {
        log.error("Consumer flux exception", e);
        recordSubmission.error(e);
        cancel();
    }

    private void cancel() {
        log.debug("cancel {}", isActive);
        if (isActive.compareAndSet(true, false)) {
            boolean isConsumerClosed = consumer == null;
            try {
                if (!isConsumerClosed) {
                    consumer.wakeup();
                    long closeStartNanos = System.nanoTime();
                    long closeEndNanos = closeStartNanos + receiverOptions.closeTimeout().toNanos();
                    CloseEvent closeEvent = new CloseEvent(closeEndNanos);
                    emit(closeEvent);
                    isConsumerClosed = closeEvent.await();
                }
            } catch (Exception e) {
                log.warn("Cancel exception: " + e);
            } finally {
                fluxList.clear();
                eventScheduler.shutdown();
                try {
                    for (Cancellation cancellation : cancellations)
                        cancellation.dispose();
                } finally {
                    // If the consumer was not closed within the specified timeout
                    // try to close again. This is not safe, so ignore exceptions and
                    // retry.
                    int maxRetries = 10;
                    for (int i = 0; i < maxRetries && !isConsumerClosed; i++) {
                        try {
                            if (consumer != null)
                                consumer.close();
                            isConsumerClosed = true;
                        } catch (Exception e) {
                            if (i == maxRetries - 1)
                                log.warn("Consumer could not be closed", e);
                        }
                    }
                    consumerFlux = null;
                    isClosed.set(true);
                }
            }
        }
    }

    private ReceiverRecord<K, V> newConsumerMessage(ConsumerRecord<K, V> consumerRecord) {
        TopicPartition topicPartition = new TopicPartition(consumerRecord.topic(), consumerRecord.partition());
        CommittableOffset committableOffset = new CommittableOffset(topicPartition, consumerRecord.offset());
        ReceiverRecord<K, V> message = new ReceiverRecord<K, V>(consumerRecord, committableOffset);
        switch (receiverOptions.ackMode()) {
            case AUTO_ACK:
                committableOffset.acknowledge();
                break;
            case ATMOST_ONCE:
                committableOffset.commit()
                                 .doOnError(e -> fail(e))
                                 .block();
                break;
            case MANUAL_ACK:
            case MANUAL_COMMIT:
                break;
            default:
                throw new IllegalStateException("Unknown ack mode " + receiverOptions.ackMode());
        }
        return message;
    }

    private void doEvent(Event<?> event) {
        log.trace("doEvent {}", event.eventType);
        try {
            event.run();
        } catch (Exception e) {
            fail(e);
        }
    }

    private void emit(Event<?> event) {
        Emission emission = eventSubmission.emit(event);
        if (emission != Emission.OK)
            log.error("Event emission failed: {} {}", event.eventType, emission);
    }

    public abstract class Event<R> implements Runnable {
        protected EventType eventType;
        Event(EventType eventType) {
            this.eventType = eventType;
        }
        public EventType eventType() {
            return eventType;
        }
    }

    private class InitEvent extends Event<ConsumerRecords<K, V>> {

        private final Consumer<Flux<InboundRecord<K, V>>> kafkaSubscribeOrAssign;
        InitEvent(Consumer<Flux<InboundRecord<K, V>>> kafkaSubscribeOrAssign) {
            super(EventType.INIT);
            this.kafkaSubscribeOrAssign = kafkaSubscribeOrAssign;
        }
        @Override
        public void run() {
            try {
                isActive.set(true);
                isClosed.set(false);
                consumer = ConsumerFactory.INSTANCE.createConsumer(receiverOptions);
                kafkaSubscribeOrAssign.accept(consumerFlux);
                consumer.poll(0); // wait for assignment
            } catch (Exception e) {
                e.printStackTrace();
                if (isActive.get()) {
                    log.error("Unexpected exception", e);
                    fail(e);
                }
            }
        }
    }

    private class PollEvent extends Event<ConsumerRecords<K, V>> {

        private final AtomicBoolean isPending = new AtomicBoolean();
        private final long pollTimeoutMs;
        PollEvent() {
            super(EventType.POLL);
            pollTimeoutMs = receiverOptions.pollTimeout().toMillis();
        }
        @Override
        public void run() {
            needsHeartbeat.set(false);
            try {
                if (isActive.get()) {
                    // Ensure that commits are not queued behind polls since number of poll events is
                    // chosen by reactor.
                    commitEvent.runIfRequired(false);
                    ConsumerRecords<K, V> records = consumer.poll(pollTimeoutMs);
                    if (records.count() > 0) {
                        Emission emission = recordSubmission.emit(records);
                        if (emission != Emission.OK)
                            log.error("Emission of consumer records failed with error " + emission);
                    }
                    isPending.compareAndSet(true, false);
                    if (requestsPending.addAndGet(0 - records.count()) > 0 && isActive.get())
                        scheduleIfRequired();
                }
            } catch (Exception e) {
                if (isActive.get()) {
                    log.error("Unexpected exception", e);
                    fail(e);
                }
            }
        }

        void scheduleIfRequired() {
            if (isPending.compareAndSet(false, true))
                emit(this);
        }
    }

    class CommitEvent extends Event<Map<TopicPartition, OffsetAndMetadata>> {
        private final CommittableBatch commitBatch;
        private final AtomicBoolean isPending = new AtomicBoolean();
        private final AtomicInteger inProgress = new AtomicInteger();
        CommitEvent() {
            super(EventType.COMMIT);
            this.commitBatch = new CommittableBatch();
        }
        @Override
        public void run() {
            isPending.set(false);
            final CommitArgs commitArgs = commitBatch.getAndClearOffsets();
            try {
                if (commitArgs != null) {
                    if (!commitArgs.offsets.isEmpty()) {
                        inProgress.incrementAndGet();
                        consumer.commitAsync(commitArgs.offsets(), (offsets, exception) -> {
                                inProgress.decrementAndGet();
                                if (exception == null)
                                    handleSuccess(commitArgs, offsets);
                                else
                                    handleFailure(commitArgs, exception);
                            });
                    } else {
                        handleSuccess(commitArgs, commitArgs.offsets);
                    }
                }
            } catch (Exception e) {
                log.error("Unexpected exception", e);
                inProgress.decrementAndGet();
                handleFailure(commitArgs, e);
            }
        }

        void runIfRequired(boolean force) {
            if (isPending.compareAndSet(true, false) || (force && receiverOptions.ackMode() != AckMode.MANUAL_COMMIT)) {
                run();
            }
        }

        private void handleSuccess(CommitArgs commitArgs, Map<TopicPartition, OffsetAndMetadata> offsets) {
            if (!offsets.isEmpty())
                consecutiveCommitFailures.set(0);
            if (commitArgs.callbackEmitters != null) {
                for (MonoSink<Void> emitter : commitArgs.callbackEmitters) {
                    emitter.success();
                }
            }
        }

        private void handleFailure(CommitArgs commitArgs, Exception exception) {
            log.warn("Commit failed", exception);
            AckMode ackMode = receiverOptions.ackMode();
            boolean mayRetry = isRetriableException(exception) &&
                    isActive.get() &&
                    consecutiveCommitFailures.incrementAndGet() < receiverOptions.maxAutoCommitAttempts() &&
                    ackMode != AckMode.MANUAL_COMMIT && ackMode != AckMode.ATMOST_ONCE;
            if (ackMode == AckMode.MANUAL_COMMIT) {
                commitBatch.restoreOffsets(commitArgs);
                for (MonoSink<Void> emitter : commitArgs.callbackEmitters()) {
                    emitter.error(exception);
                }
            } else if (!mayRetry) {
                fail(exception);
            } else {
                commitBatch.restoreOffsets(commitArgs);
                log.error("Commit failed with exception" + exception + ", retries remaining " + (receiverOptions.maxAutoCommitAttempts() - consecutiveCommitFailures.get()));
            }
        }

        private void scheduleIfRequired() {
            if (isPending.compareAndSet(false, true))
                emit(this);
        }

        private void waitFor(long endTimeNanos) {
            while (inProgress.get() > 0 && endTimeNanos - System.nanoTime() > 0) {
                consumer.poll(1);
            }
        }

        protected boolean isRetriableException(Exception exception) {
            return exception instanceof RetriableCommitFailedException;
        }
    }

    private class HeartbeatEvent extends Event<Void> {
        HeartbeatEvent() {
            super(EventType.HEARTBEAT);
        }
        @Override
        public void run() {
            if (isActive.get() && needsHeartbeat.getAndSet(true)) {
                consumer.pause(consumer.assignment());
                consumer.poll(0);
                consumer.resume(consumer.assignment());
            }
        }
    }

    private class CloseEvent extends Event<ConsumerRecords<K, V>> {
        private final long closeEndTimeNanos;
        private Semaphore semaphore = new Semaphore(0);
        CloseEvent(long closeEndTimeNanos) {
            super(EventType.CLOSE);
            this.closeEndTimeNanos = closeEndTimeNanos;
        }
        @Override
        public void run() {
            try {
                if (consumer != null) {
                    try {
                        consumer.poll(0);
                    } catch (WakeupException e) {
                        // ignore
                    }
                    commitEvent.runIfRequired(true);
                    commitEvent.waitFor(closeEndTimeNanos);
                    consumer.close();
                }
                semaphore.release();
            } catch (Exception e) {
                log.error("Unexpected exception", e);
                fail(e);
            }
        }
        boolean await(long timeoutNanos) throws InterruptedException {
            return semaphore.tryAcquire(timeoutNanos, TimeUnit.NANOSECONDS);
        }
        boolean await() {
            boolean closed = false;
            long remainingNanos;
            while (!closed && (remainingNanos = closeEndTimeNanos - System.nanoTime()) > 0) {
                try {
                    closed = await(remainingNanos);
                } catch (InterruptedException e) {
                    // ignore
                }
            }
            return closed;
        }
    }

    protected class CommittableOffset implements Offset {

        private final TopicPartition topicPartition;
        private final long commitOffset;
        private final AtomicBoolean acknowledged;

        public CommittableOffset(TopicPartition topicPartition, long nextOffset) {
            this.topicPartition = topicPartition;
            this.commitOffset = nextOffset;
            this.acknowledged = new AtomicBoolean(false);
        }

        @Override
        public Mono<Void> commit() {
            if (maybeUpdateOffset() > 0)
                return scheduleCommit();
            else
                return Mono.empty();
        }

        @Override
        public void acknowledge() {
            switch (receiverOptions.ackMode()) {
                case ATMOST_ONCE:
                    break;
                case AUTO_ACK:
                case MANUAL_ACK:
                    int commitBatchSize = receiverOptions.commitBatchSize();
                    if (commitBatchSize > 0 && maybeUpdateOffset() >= commitBatchSize)
                        commitEvent.scheduleIfRequired();
                    break;
                case MANUAL_COMMIT:
                    maybeUpdateOffset();
                    break;
                default:
                    throw new IllegalStateException("Unknown commit mode " + receiverOptions.ackMode());
            }
        }

        @Override
        public TopicPartition topicPartition() {
            return topicPartition;
        }

        @Override
        public long offset() {
            return commitOffset;
        }

        private int maybeUpdateOffset() {
            if (acknowledged.compareAndSet(false, true))
                return commitEvent.commitBatch.updateOffset(topicPartition, commitOffset);
            else
                return commitEvent.commitBatch.batchSize();

        }

        private Mono<Void> scheduleCommit() {
            return Mono.create(emitter -> {
                    commitEvent.commitBatch.addCallbackEmitter(emitter);
                    commitEvent.scheduleIfRequired();
                });
        }

        @Override
        public String toString() {
            return topicPartition + "@" + commitOffset;
        }
    }
}
