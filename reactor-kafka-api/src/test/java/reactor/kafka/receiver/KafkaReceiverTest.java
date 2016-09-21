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
package reactor.kafka.receiver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.Cancellation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.AbstractKafkaTest;
import reactor.kafka.receiver.internals.TestableReceiver;
import reactor.kafka.sender.internals.KafkaSender;
import reactor.kafka.util.TestSubscriber;
import reactor.kafka.util.TestUtils;
import reactor.util.function.Tuples;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertNotEquals;

public class KafkaReceiverTest extends AbstractKafkaTest {

    private static final Logger log = LoggerFactory.getLogger(KafkaReceiverTest.class.getName());

    private KafkaSender<Integer, String> kafkaSender;

    private Scheduler consumerScheduler;
    private Semaphore assignSemaphore = new Semaphore(0);
    private List<Cancellation> subscribeCancellations = new ArrayList<>();
    private TestSubscriber<ReceiverMessage<Integer, String>> testSubscriber;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        kafkaSender = new KafkaSender<>(senderOptions);
        consumerScheduler = Schedulers.newParallel("test-consumer");
    }

    @After
    public void tearDown() {
        if (testSubscriber != null)
            testSubscriber.cancel();
        cancelSubscriptions(true);
        kafkaSender.close();
        consumerScheduler.shutdown();
        Schedulers.shutdownNow();
    }

    @Test
    public final void sendReceiveTest() throws Exception {
        Flux<ReceiverMessage<Integer, String>> kafkaFlux = createTestFlux(null).kafkaFlux();
        sendReceive(kafkaFlux, 0, 100, 0, 100);
    }

    @Test
    public final void seekToBeginningTest() throws Exception {
        int count = 10;
        sendMessages(0, count);
        receiverOptions = receiverOptions.ackMode(AckMode.AUTO_ACK);
        Flux<ReceiverMessage<Integer, String>> kafkaFlux =
                Receiver.create(receiverOptions)
                        .doOnPartitionsAssigned(this::seekToBeginning)
                        .receive(Collections.singletonList(topic));

        sendReceive(kafkaFlux, count, count, 0, count * 2);
    }

    @Test
    public final void seekToEndTest() throws Exception {
        int count = 10;
        sendMessages(0, count);
        receiverOptions = receiverOptions.ackMode(AckMode.AUTO_ACK);
        Flux<ReceiverMessage<Integer, String>> kafkaFlux =
                Receiver.create(receiverOptions)
                        .doOnPartitionsAssigned(partitions -> {
                                for (ReceiverPartition partition : partitions)
                                    partition.seekToEnd();
                                onPartitionsAssigned(partitions);
                            })
                        .receive(Collections.singletonList(topic));

        sendReceiveWithSendDelay(kafkaFlux, Duration.ofMillis(100), count, count);
    }

    @Test
    public final void seekTest() throws Exception {
        int count = 10;
        sendMessages(0, count);
        receiverOptions = receiverOptions.ackMode(AckMode.AUTO_ACK);
        Flux<ReceiverMessage<Integer, String>> kafkaFlux =
                Receiver.create(receiverOptions)
                        .doOnPartitionsAssigned(partitions -> {
                                onPartitionsAssigned(partitions);
                                for (ReceiverPartition partition : partitions)
                                     partition.seek(1);
                            })
                        .receive(Collections.singletonList(topic))
                        .doOnError(e -> log.error("KafkaFlux exception", e));

        sendReceive(kafkaFlux, count, count, partitions, count * 2 - partitions);
    }

    @Test
    public final void wildcardSubscribeTest() throws Exception {
        receiverOptions = receiverOptions.ackMode(AckMode.AUTO_ACK);
        Flux<ReceiverMessage<Integer, String>> kafkaFlux =
                Receiver.create(receiverOptions)
                        .doOnPartitionsAssigned(this::onPartitionsAssigned)
                        .receive(Pattern.compile("test.*"));
        sendReceive(kafkaFlux, 0, 10, 0, 10);
    }

    @Test
    public final void manualAssignmentTest() throws Exception {
        receiverOptions = receiverOptions.ackMode(AckMode.AUTO_ACK);
        Flux<ReceiverMessage<Integer, String>> kafkaFlux =
                Receiver.create(receiverOptions)
                        .receivePartitions(getTopicPartitions())
                        .doOnSubscribe(s -> assignSemaphore.release());
        sendReceiveWithSendDelay(kafkaFlux, Duration.ofMillis(1000), 0, 10);
    }

    @Test
    public final void autoAckTest() throws Exception {
        Flux<ReceiverMessage<Integer, String>> kafkaFlux = createTestFlux(AckMode.AUTO_ACK).kafkaFlux();
        sendReceive(kafkaFlux, 0, 100, 0, 100);

        // Close consumer and create another one. First consumer should commit final offset on close.
        // Second consumer should receive only new messages.
        cancelSubscriptions(true);
        clearReceivedMessages();
        Flux<ReceiverMessage<Integer, String>> kafkaFlux2 = createTestFlux(AckMode.AUTO_ACK).kafkaFlux();
        sendReceive(kafkaFlux2, 100, 100, 100, 100);
    }

    @Test
    public final void atmostOnceTest() throws Exception {
        receiverOptions.closeTimeout(Duration.ofMillis(1000));
        TestableReceiver testFlux = createTestFlux(AckMode.ATMOST_ONCE);
        sendReceive(testFlux.kafkaFlux(), 0, 100, 0, 100);

        // Second consumer should receive only new messages even though first one was not closed gracefully
        restartAndCheck(testFlux, 100, 100, 0);
    }

    @Test
    public final void atleastOnceCommitRecord() throws Exception {
        receiverOptions.closeTimeout(Duration.ofMillis(1000));
        receiverOptions.commitBatchSize(1);
        receiverOptions.commitInterval(Duration.ofMillis(60000));
        TestableReceiver testFlux = createTestFlux(AckMode.MANUAL_ACK);
        Flux<ReceiverMessage<Integer, String>> fluxWithAck = testFlux.kafkaFlux().doOnNext(record -> record.offset().acknowledge());
        sendReceive(fluxWithAck, 0, 100, 0, 100);

        // Atmost one record may be redelivered
        restartAndCheck(testFlux, 100, 100, 1);
    }

    @Test
    public final void atleastOnceCommitBatchSize() throws Exception {
        receiverOptions.closeTimeout(Duration.ofMillis(1000));
        receiverOptions.commitBatchSize(10);
        receiverOptions.commitInterval(Duration.ofMillis(60000));
        TestableReceiver testFlux = createTestFlux(AckMode.MANUAL_ACK);
        Flux<ReceiverMessage<Integer, String>> fluxWithAck = testFlux.kafkaFlux().doOnNext(record -> record.offset().acknowledge());
        sendReceive(fluxWithAck, 0, 100, 0, 100);

        /// Atmost batchSize records may be redelivered
        restartAndCheck(testFlux, 100, 100, receiverOptions.commitBatchSize());
    }

    @Test
    public final void atleastOnceCommitInterval() throws Exception {
        receiverOptions.closeTimeout(Duration.ofMillis(1000));
        receiverOptions.commitBatchSize(Integer.MAX_VALUE);
        receiverOptions.commitInterval(Duration.ofMillis(1000));
        TestableReceiver testFlux = createTestFlux(AckMode.MANUAL_ACK);
        Flux<ReceiverMessage<Integer, String>> fluxWithAck = testFlux.kafkaFlux().doOnNext(record -> record.offset().acknowledge());
        sendReceive(fluxWithAck, 0, 100, 0, 100);
        Thread.sleep(1500);

        restartAndCheck(testFlux, 100, 100, 0);
    }

    @Test
    public final void atleastOnceCommitIntervalOrCount() throws Exception {
        receiverOptions.closeTimeout(Duration.ofMillis(1000));
        receiverOptions.commitBatchSize(10);
        receiverOptions.commitInterval(Duration.ofMillis(1000));
        TestableReceiver testFlux = createTestFlux(AckMode.MANUAL_ACK);
        Flux<ReceiverMessage<Integer, String>> fluxWithAck = testFlux.kafkaFlux().doOnNext(record -> record.offset().acknowledge());
        sendReceive(fluxWithAck, 0, 100, 0, 100);

        restartAndCheck(testFlux, 100, 100, receiverOptions.commitBatchSize());
        expectedMessages.forEach(list -> list.clear());
        cancelSubscriptions(true);

        receiverOptions.commitBatchSize(1000);
        receiverOptions.commitInterval(Duration.ofMillis(100));
        testFlux = createTestFlux(AckMode.MANUAL_ACK);
        fluxWithAck = testFlux.kafkaFlux().doOnNext(record -> record.offset().acknowledge());
        sendReceive(testFlux.kafkaFlux(), 200, 100, 200, 100);

        Thread.sleep(1000);
        restartAndCheck(testFlux, 300, 100, 0);
    }

    @Test
    public final void atleastOnceCloseTest() throws Exception {
        receiverOptions = receiverOptions.closeTimeout(Duration.ofMillis(1000))
                                         .commitBatchSize(10)
                                         .commitInterval(Duration.ofMillis(60000))
                                         .consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        TestableReceiver testReceiver = createTestFlux(AckMode.MANUAL_ACK);
        Flux<ReceiverMessage<Integer, String>> fluxWithAck = testReceiver.kafkaFlux().doOnNext(record -> {
                if (receivedMessages.get(record.record().partition()).size() < 10)
                    record.offset().acknowledge();
            });
        sendReceive(fluxWithAck, 0, 100, 0, 100);

        // Check that close commits ack'ed records, does not commit un-ack'ed records
        cancelSubscriptions(true);
        clearReceivedMessages();
        Flux<ReceiverMessage<Integer, String>> kafkaFlux2 = createTestFlux(AckMode.AUTO_ACK).kafkaFlux();
        sendReceive(kafkaFlux2, 100, 100, 10 * partitions, 200 - (10 * partitions));
    }

    @Test
    public final void manualCommitRecordAsyncTest() throws Exception {
        int count = 10;
        CountDownLatch commitLatch = new CountDownLatch(count);
        long[] committedOffsets = new long[partitions];
        receiverOptions = receiverOptions.ackMode(AckMode.MANUAL_COMMIT);
        Flux<ReceiverMessage<Integer, String>> kafkaFlux =
                Receiver.create(receiverOptions)
                        .doOnPartitionsAssigned(this::seekToBeginning)
                        .receive(Collections.singletonList(topic))
                        .doOnNext(record -> record.offset()
                                                  .commit()
                                                  .doOnSuccess(i -> onCommit(record, commitLatch, committedOffsets))
                                                  .doOnError(e -> log.error("Commit exception", e))
                                                  .subscribe());

        subscribe(kafkaFlux, new CountDownLatch(count));
        sendMessages(0, count);
        checkCommitCallbacks(commitLatch, committedOffsets);
    }

    @Test
    public final void manualCommitFailureTest() throws Exception {
        int count = 1;

        AtomicBoolean commitSuccess = new AtomicBoolean();
        Semaphore commitErrorSemaphore = new Semaphore(0);
        TestableReceiver testFlux = createTestFlux(AckMode.MANUAL_COMMIT);
        Flux<ReceiverMessage<Integer, String>> kafkaFlux = testFlux.kafkaFlux()
                         .doOnNext(record -> {
                                 ReceiverOffset offset = record.offset();
                                 TestableReceiver.setNonExistentPartition(offset);
                                 record.offset().acknowledge();
                                 record.offset().commit()
                                       .doOnError(e -> commitErrorSemaphore.release())
                                       .doOnSuccess(i -> commitSuccess.set(true))
                                       .subscribe();
                             })
                         .doOnError(e -> log.error("KafkaFlux exception", e));

        subscribe(kafkaFlux, new CountDownLatch(count));
        sendMessages(1, count);
        assertTrue("Commit error callback not invoked", commitErrorSemaphore.tryAcquire(receiveTimeoutMillis, TimeUnit.MILLISECONDS));
        assertFalse("Commit of non existent topic succeeded", commitSuccess.get());
    }

    @Test
    public final void manualCommitSyncTest() throws Exception {
        int count = 10;
        CountDownLatch commitLatch = new CountDownLatch(count);
        long[] committedOffsets = new long[partitions];
        for (int i = 0; i < committedOffsets.length; i++)
            committedOffsets[i] = 0;
        Flux<ReceiverMessage<Integer, String>> kafkaFlux = createTestFlux(AckMode.MANUAL_COMMIT).kafkaFlux()
                         .doOnNext(record -> {
                                 assertEquals(committedOffsets[record.record().partition()], record.record().offset());
                                 record.offset().commit()
                                       .doOnSuccess(i -> onCommit(record, commitLatch, committedOffsets))
                                       .subscribe()
                                       .block();
                             })
                         .doOnError(e -> log.error("KafkaFlux exception", e));

        sendAndWaitForMessages(kafkaFlux, count);
        checkCommitCallbacks(commitLatch, committedOffsets);
    }

    @Test
    public final void manualCommitBatchTest() throws Exception {
        int count = 20;
        int commitIntervalMessages = 4;
        CountDownLatch commitLatch = new CountDownLatch(count / commitIntervalMessages);
        long[] committedOffsets = new long[partitions];
        for (int i = 0; i < committedOffsets.length; i++)
            committedOffsets[i] = -1;
        List<ReceiverOffset> uncommitted = new ArrayList<>();
        Flux<ReceiverMessage<Integer, String>> kafkaFlux = createTestFlux(AckMode.MANUAL_COMMIT).kafkaFlux()
                         .doOnNext(record -> {
                                 ReceiverOffset offset = record.offset();
                                 offset.acknowledge();
                                 uncommitted.add(offset);
                                 if (uncommitted.size() == commitIntervalMessages) {
                                     offset.commit()
                                           .doOnSuccess(i -> onCommit(uncommitted, commitLatch, committedOffsets))
                                           .doOnError(e -> log.error("Commit exception", e))
                                           .subscribe()
                                           .block();
                                 }
                             })
                         .doOnError(e -> log.error("KafkaFlux exception", e));

        sendAndWaitForMessages(kafkaFlux, count);
        checkCommitCallbacks(commitLatch, committedOffsets);
    }

    @Test
    public final void manualCommitCloseTest() throws Exception {
        CountDownLatch commitLatch = new CountDownLatch(4);
        long[] committedOffsets = new long[partitions];
        receiverOptions = receiverOptions.ackMode(AckMode.MANUAL_COMMIT);
        Flux<ReceiverMessage<Integer, String>> kafkaFlux =
                Receiver.create(receiverOptions)
                        .doOnPartitionsAssigned(this::seekToBeginning)
                        .receive(Collections.singletonList(topic))
                        .doOnNext(record -> {
                                int messageCount = receivedMessages.get(record.record().partition()).size();
                                if (messageCount < 10 && ((messageCount + 1) % 5) == 0) {
                                    record.offset()
                                          .commit()
                                          .doOnSuccess(i -> onCommit(record, commitLatch, committedOffsets))
                                          .doOnError(e -> log.error("Commit exception", e))
                                          .subscribe();
                                }
                            });

        sendReceive(kafkaFlux, 0, 100, 0, 100);

        // Check that close commits pending committed records, but does not commit ack'ed uncommitted records
        cancelSubscriptions(true);
        clearReceivedMessages();
        Flux<ReceiverMessage<Integer, String>> kafkaFlux2 = createTestFlux(AckMode.AUTO_ACK).kafkaFlux();
        sendReceive(kafkaFlux2, 100, 100, 40, 160);
    }

    @Test
    public void manualCommitRetry() throws Exception {
        testManualCommitRetry(true);
    }

    @Test
    public void manualCommitNonRetriableException() throws Exception {
        testManualCommitRetry(false);
    }

    // Manual commits should be retried regardless of the type of exception. It is up to the application
    // to provide a predicate that allows retries.
    private void testManualCommitRetry(boolean retriableException) throws Exception {
        int count = 1;
        int failureCount = 2;
        Semaphore commitSuccessSemaphore = new Semaphore(0);
        Semaphore commitFailureSemaphore = new Semaphore(0);
        TestableReceiver testFlux = createTestFlux(AckMode.MANUAL_COMMIT);
        Flux<ReceiverMessage<Integer, String>> flux = testFlux.withManualCommitFailures(retriableException, failureCount, commitSuccessSemaphore, commitFailureSemaphore);

        subscribe(flux, new CountDownLatch(count));
        sendMessages(1, count);
        assertTrue("Commit did not succeed after retry", commitSuccessSemaphore.tryAcquire(receiveTimeoutMillis, TimeUnit.MILLISECONDS));
        assertEquals(failureCount,  commitFailureSemaphore.availablePermits());
    }

    @Test
    public void autoCommitRetry() throws Exception {
        int count = 5;
        testAutoCommitFailureScenarios(true, count, 10, 0, 2);

        Flux<ReceiverMessage<Integer, String>> flux = createTestFlux(AckMode.AUTO_ACK).kafkaFlux();
        sendReceive(flux, count, count, count, count);
    }

    @Test
    public void autoCommitNonRetriableException() throws Exception {
        int count = 5;
        receiverOptions = receiverOptions.consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                               .maxAutoCommitAttempts(2);
        testAutoCommitFailureScenarios(false, count, 1000, 0, 10);

        Flux<ReceiverMessage<Integer, String>> kafkaFlux = createTestFlux(AckMode.AUTO_ACK).kafkaFlux();
        sendReceiveWithRedelivery(kafkaFlux, count, count, 3, 5);
    }

    @Test
    public void autoCommitFailurePropagationAfterRetries() throws Exception {
        int count = 5;
        receiverOptions = receiverOptions.consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                               .maxAutoCommitAttempts(2);
        testAutoCommitFailureScenarios(true, count, 2, 0, Integer.MAX_VALUE);

        Flux<ReceiverMessage<Integer, String>> kafkaFlux = createTestFlux(AckMode.MANUAL_ACK).kafkaFlux();
        sendReceiveWithRedelivery(kafkaFlux, count, count, 2, 5);
    }

    private void testAutoCommitFailureScenarios(boolean retriable, int count, int maxAttempts,
            int errorInjectIndex, int errorClearIndex) throws Exception {
        AtomicBoolean failed = new AtomicBoolean();
        receiverOptions = receiverOptions.commitBatchSize(1)
                               .commitInterval(Duration.ofMillis(1000))
                               .maxAutoCommitAttempts(maxAttempts)
                               .closeTimeout(Duration.ofMillis(1000))
                               .consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        TestableReceiver testFlux = createTestFlux(AckMode.MANUAL_ACK);
        Semaphore onNextSemaphore = new Semaphore(0);
        Flux<ReceiverMessage<Integer, String>> flux = testFlux.kafkaFlux()
                  .doOnSubscribe(s -> {
                          if (retriable)
                              testFlux.injectCommitEventForRetriableException();
                      })
                  .doOnNext(record -> {
                          int receiveCount = count(receivedMessages);
                          if (receiveCount == errorInjectIndex)
                              testFlux.injectCommitError();
                          if (receiveCount >= errorClearIndex)
                              testFlux.clearCommitError();
                          record.offset().acknowledge();
                          onNextSemaphore.release();
                      })
                  .doOnError(e -> failed.set(true));
        subscribe(flux, new CountDownLatch(count));
        for (int i = 0; i < count; i++) {
            sendMessages(i, 1);
            if (!failed.get()) {
                onNextSemaphore.tryAcquire(requestTimeoutMillis, TimeUnit.MILLISECONDS);
                TestUtils.sleep(receiverOptions.pollTimeout().toMillis());
            }
        }

        boolean failureExpected = !retriable || errorClearIndex > count;
        assertEquals(failureExpected, failed.get());
        if (failureExpected) {
            testFlux.waitForClose();
        }
        cancelSubscriptions(true);
        testFlux.waitForClose();
        clearReceivedMessages();
    }

    @Test
    public final void transferMessagesTest() throws Exception {
        int count = 10;
        CountDownLatch sendLatch0 = new CountDownLatch(count);
        CountDownLatch sendLatch1 = new CountDownLatch(count);
        CountDownLatch receiveLatch0 = new CountDownLatch(count);
        CountDownLatch receiveLatch1 = new CountDownLatch(count);
        // Subscribe on partition 1
        Flux<ReceiverMessage<Integer, String>> partition1Flux =
                Receiver.create(createReceiverOptions(null, "group2"))
                        .doOnPartitionsAssigned(this::seekToBeginning)
                        .doOnPartitionsAssigned(this::onPartitionsAssigned)
                        .receivePartitions(Collections.singletonList(new TopicPartition(topic, 1)))
                        .doOnError(e -> log.error("KafkaFlux exception", e));
        subscribe(partition1Flux, receiveLatch1);

        // Receive from partition 0 and send to partition 1
        Flux<ReceiverMessage<Integer, String>> flux0 = Receiver.create(receiverOptions.ackMode(AckMode.MANUAL_COMMIT))
                .doOnPartitionsAssigned(this::seekToBeginning)
                .receivePartitions(Collections.singletonList(new TopicPartition(topic, 0)))
                .doOnNext(cr -> receiveLatch0.countDown())
                .publishOn(consumerScheduler);
        Cancellation cancellation0 = kafkaSender.send(flux0.map(cr -> Tuples.of(new ProducerRecord<>(topic, 1, cr.record().key(), cr.record().value()), cr.offset())))
                    .doOnNext(sendResult -> {
                            sendResult.getT2()
                                      .commit()
                                      .subscribe()
                                      .block();
                            sendLatch1.countDown();
                        })
                    .doOnError(e -> log.error("KafkaFlux exception", e))
                    .subscribe();
        subscribeCancellations.add(cancellation0);

        // Send messages to partition 0
        kafkaSender.send(Flux.range(0, count)
                             .map(i -> Tuples.of(new ProducerRecord<Integer, String>(topic, 0, i, "Message " + i), null))
                   .doOnNext(metadata -> sendLatch0.countDown()))
                   .subscribe();

        if (!sendLatch0.await(receiveTimeoutMillis, TimeUnit.MILLISECONDS))
            fail(sendLatch0.getCount() + " messages not sent to partition 0");
        waitForMessages(receiveLatch0);
        if (!sendLatch1.await(receiveTimeoutMillis, TimeUnit.MILLISECONDS))
            fail(sendLatch1.getCount() + " messages not sent to partition 1");

        // Check messages received on partition 1
        waitForMessages(receiveLatch1);
    }

    @Test
    public final void heartbeatTest() throws Exception {
        int count = 5;
        this.receiveTimeoutMillis = sessionTimeoutMillis * 5;
        CountDownLatch latch = new CountDownLatch(count);
        AtomicInteger revoked = new AtomicInteger();
        AtomicInteger commitFailures = new AtomicInteger();
        Semaphore commitSemaphore = new Semaphore(0);
        testSubscriber = TestSubscriber.create(1);
        Flux<ReceiverMessage<Integer, String>> kafkaFlux =
                Receiver.create(receiverOptions.ackMode(AckMode.MANUAL_COMMIT))
                        .doOnPartitionsRevoked(partitions -> revoked.addAndGet(partitions.size()))
                        .doOnPartitionsAssigned(this::onPartitionsAssigned)
                        .receive(Collections.singletonList(topic))
                        .doOnNext(record -> {
                                latch.countDown();
                                onReceive(record.record());
                                if (count - latch.getCount() == 1)
                                    TestUtils.sleep(sessionTimeoutMillis + 1000);
                                record.offset()
                                      .commit()
                                      .doOnError(e -> commitFailures.incrementAndGet())
                                      .doOnSuccess(v -> commitSemaphore.release())
                                      .subscribe();
                                testSubscriber.request(1);
                            });

        kafkaFlux.subscribe(testSubscriber);
        testSubscriber.assertSubscribed();
        assertTrue("Partitions not assigned", assignSemaphore.tryAcquire(sessionTimeoutMillis + 1000, TimeUnit.MILLISECONDS));
        sendMessages(0, count);
        waitForMessages(latch);
        assertTrue("Commits did not succeed", commitSemaphore.tryAcquire(count, requestTimeoutMillis * count, TimeUnit.MILLISECONDS));
        assertEquals(0, commitFailures.get());
        assertEquals(0, revoked.get());
    }

    @Test
    public final void brokerRestartTest() throws Exception {
        int sendBatchSize = 10;
        receiverOptions = receiverOptions.maxAutoCommitAttempts(1000);
        Flux<ReceiverMessage<Integer, String>> kafkaFlux = createTestFlux(AckMode.AUTO_ACK).kafkaFlux()
                         .doOnError(e -> log.error("KafkaFlux exception", e));

        CountDownLatch receiveLatch = new CountDownLatch(sendBatchSize * 2);
        subscribe(kafkaFlux, receiveLatch);
        sendMessagesSync(0, sendBatchSize);
        shutdownKafkaBroker();
        TestUtils.sleep(2000);
        restartKafkaBroker();
        sendMessagesSync(sendBatchSize, sendBatchSize);
        waitForMessages(receiveLatch);
        checkConsumedMessages();
    }

    @Test
    public final void closeTest() throws Exception {
        int count = 10;
        for (int i = 0; i < 2; i++) {
            Collection<ReceiverPartition> seekablePartitions = new ArrayList<>();
            Flux<ReceiverMessage<Integer, String>> kafkaFlux =
                    Receiver.create(receiverOptions.ackMode(AckMode.AUTO_ACK))
                            .doOnPartitionsAssigned(partitions -> {
                                    seekablePartitions.addAll(partitions);
                                    assignSemaphore.release();
                                })
                            .receive(Collections.singletonList(topic));

            Cancellation cancellation = sendAndWaitForMessages(kafkaFlux, count);
            assertTrue("No partitions assigned", seekablePartitions.size() > 0);
            cancellation.dispose();
            try {
                seekablePartitions.iterator().next().seekToBeginning();
                fail("Consumer not closed");
            } catch (IllegalStateException e) {
                // expected exception
            }
        }
    }

    @Test
    public final void multiConsumerTest() throws Exception {
        int count = 100;
        CountDownLatch latch = new CountDownLatch(count);
        @SuppressWarnings("unchecked")
        Flux<ReceiverMessage<Integer, String>>[] kafkaFlux = (Flux<ReceiverMessage<Integer, String>>[]) new Flux[partitions];
        AtomicInteger[] receiveCount = new AtomicInteger[partitions];
        for (int i = 0; i < partitions; i++) {
            final int id = i;
            receiveCount[i] = new AtomicInteger();
            kafkaFlux[i] = createTestFlux(AckMode.AUTO_ACK).kafkaFlux()
                             .publishOn(consumerScheduler)
                             .doOnNext(record -> {
                                     receiveCount[id].incrementAndGet();
                                     onReceive(record.record());
                                     latch.countDown();
                                 })
                             .doOnError(e -> log.error("KafkaFlux exception", e));
            subscribeCancellations.add(kafkaFlux[i].subscribe());
            waitFoPartitionAssignment();
        }
        sendMessages(0, count);
        waitForMessages(latch);
        checkConsumedMessages(0, count);
    }

    /**
     * Tests groupBy(partition) with guaranteed ordering through thread affinity for each partition.
     * <p/>
     * When there are as many threads in the scheduler as partitions, groupBy(partition) enables
     * each partition to be processed on its own thread. All partitions can make progress concurrently
     * without delays on any partition affecting others.
     */
    @Test
    public final void groupByPartitionTest() throws Exception {
        int count = 10000;
        Flux<ReceiverMessage<Integer, String>> kafkaFlux = createTestFlux(AckMode.MANUAL_ACK).kafkaFlux();
        CountDownLatch latch = new CountDownLatch(count);
        Scheduler scheduler = Schedulers.newParallel("test-groupBy", partitions);
        AtomicInteger concurrentExecutions = new AtomicInteger();
        AtomicInteger concurrentPartitionExecutions = new AtomicInteger();
        Map<Integer, String> inProgressMap = new ConcurrentHashMap<>();

        Random random = new Random();
        int maxProcessingMs = 5;
        this.receiveTimeoutMillis = maxProcessingMs * count + 5000;

        //Hooks.onOperator(p -> p.ifParallelFlux().log("reactor.", Level.INFO, true,
        //        SignalType.ON_NEXT));

        Cancellation cancellation =
            kafkaFlux.groupBy(m -> m.offset().topicPartition())
                     .subscribe(partitionFlux -> subscribeCancellations.add(partitionFlux.publishOn(scheduler).subscribe(record -> {
                             int partition = record.record().partition();
                             String current = Thread.currentThread().getName() + ":" + record.offset();
                             String inProgress = inProgressMap.putIfAbsent(partition, current);
                             if (inProgress != null) {
                                 log.error("Concurrent execution on partition {} current={}, inProgress={}", partition, current, inProgress);
                                 concurrentPartitionExecutions.incrementAndGet();
                             }
                             if (inProgressMap.size() > 1)
                                 concurrentExecutions.incrementAndGet();
                             TestUtils.sleep(random.nextInt(maxProcessingMs));
                             onReceive(record.record());
                             latch.countDown();
                             record.offset().acknowledge();
                             inProgressMap.remove(partition);
                         })));
        subscribeCancellations.add(cancellation);

        try {
            waitFoPartitionAssignment();
            sendMessages(0, count);
            waitForMessages(latch);
            assertEquals("Concurrent executions on partition", 0, concurrentPartitionExecutions.get());
            checkConsumedMessages(0, count);
            assertNotEquals("No concurrent executions across partitions", 0, concurrentExecutions.get());

            Hooks.resetOnOperator();
        } finally {
            scheduler.shutdown();
        }
    }

    /**
     * Tests elastic scheduler with groupBy(partition) for a consumer processing large number of partitions.
     * <p/>
     * When there are a large number of partitions, groupBy(partition) with an elastic scheduler creates as many
     * threads as partitions unless the flux itself is bounded (here each partition flux is limited with take()).
     * In general, it may be better to group the partitions together in groupBy() to limit the number of threads
     * when using elastic scheduler with a large number of partitions
     */
    @Ignore // RS: FIXME Intermittent failure
    @Test
    public final void groupByPartitionElasticSchedulingTest() throws Exception {
        int countPerPartition = 100;
        createNewTopic("largetopic", 20);
        Flux<ReceiverMessage<Integer, String>> kafkaFlux = createTestFlux(AckMode.AUTO_ACK).kafkaFlux();
        CountDownLatch[] latch = new CountDownLatch[partitions];
        for (int i = 0; i < partitions; i++)
            latch[i] = new CountDownLatch(countPerPartition);
        Scheduler scheduler = Schedulers.newElastic("test-groupBy", 10, true);
        Map<String, Set<Integer>> threadMap = new ConcurrentHashMap<>();

        Cancellation cancellation =
            kafkaFlux.groupBy(m -> m.offset().topicPartition().partition())
                     .subscribe(partitionFlux -> partitionFlux.take(countPerPartition).publishOn(scheduler, 1).subscribe(record -> {
                             String thread = Thread.currentThread().getName();
                             int partition = record.record().partition();
                             Set<Integer> partitionSet = threadMap.get(thread);
                             if (partitionSet == null) {
                                 partitionSet = new HashSet<Integer>();
                                 threadMap.put(thread, partitionSet);
                             }
                             partitionSet.add(partition);
                             onReceive(record.record());
                             latch[partition].countDown();
                         }));
        subscribeCancellations.add(cancellation);

        try {
            waitFoPartitionAssignment();
            sendMessagesToPartition(0, 0, countPerPartition);
            waitForMessages(latch[0]);
            for (int i = 1; i < 10; i++)
                sendMessagesToPartition(i, i * countPerPartition, countPerPartition);
            for (int i = 1; i < 10; i++)
                waitForMessagesFromPartition(latch[i], i);
            assertTrue("Threads not allocated elastically " + threadMap, threadMap.size() > 1 && threadMap.size() <= 10);
            for (int i = 10; i < partitions; i++)
                sendMessagesToPartition(i, i * countPerPartition, countPerPartition);
            for (int i = 10; i < partitions; i++)
                waitForMessagesFromPartition(latch[i], i);
            assertTrue("Threads not allocated elastically " + threadMap, threadMap.size() > 1 && threadMap.size() < partitions);
            checkConsumedMessages(0, countPerPartition * partitions);
        } finally {
            scheduler.shutdown();
        }
    }

    /**
     * Tests groupBy(partition) with a large number of partitions distributed on a small number of threads.
     * Ordering is guaranteed for partitions with thread affinity. Delays in processing one partition
     * affect all partitions on that thread.
     */
    @Test
    public final void groupByPartitionThreadSharingTest() throws Exception {
        int count = 1000;
        createNewTopic("largetopic", 20);
        Flux<ReceiverMessage<Integer, String>> kafkaFlux = createTestFlux(AckMode.AUTO_ACK).kafkaFlux();
        CountDownLatch latch = new CountDownLatch(count);
        int parallelism = 4;
        Scheduler scheduler = Schedulers.newParallel("test-groupBy", parallelism);
        Map<String, Set<Integer>> threadMap = new ConcurrentHashMap<>();
        Set<Integer> inProgress = new HashSet<Integer>();
        AtomicInteger maxInProgress = new AtomicInteger();

        Cancellation cancellation =
            kafkaFlux.groupBy(m -> m.offset().topicPartition())
                     .subscribe(partitionFlux -> subscribeCancellations.add(partitionFlux.publishOn(scheduler, 1).subscribe(record -> {
                             int partition = record.record().partition();
                             String thread = Thread.currentThread().getName();
                             Set<Integer> partitionSet = threadMap.get(thread);
                             if (partitionSet == null) {
                                 partitionSet = new HashSet<Integer>();
                                 threadMap.put(thread, partitionSet);
                             }
                             partitionSet.add(partition);
                             onReceive(record.record());
                             latch.countDown();
                             synchronized (KafkaReceiverTest.this) {
                                 if (receivedMessages.get(partition).size() == count / partitions)
                                     inProgress.remove(partition);
                                 else if (inProgress.add(partition))
                                     maxInProgress.incrementAndGet();
                             }
                         })));
        subscribeCancellations.add(cancellation);

        try {
            waitFoPartitionAssignment();
            sendMessages(0, count);
            waitForMessages(latch);
            checkConsumedMessages(0, count);
            assertEquals(parallelism, threadMap.size());
            // Thread assignment is currently not perfectly balanced, hence the lenient check
            for (Map.Entry<String, Set<Integer>> entry : threadMap.entrySet())
                assertTrue("Thread assignment not balanced: " + threadMap, entry.getValue().size() > 1);
            assertEquals(partitions, maxInProgress.get());
        } finally {
            scheduler.shutdown();
        }
    }

    /**
     * Tests parallel processing without grouping by partition. This does not guarantee
     * partition-based message ordering. Long processing time on one rail enables other
     * rails to continue (but a whole rail is delayed).
     */
    @Ignore // RS: FIXME
    @Test
    public final void parallelRoundRobinSchedulerTest() throws Exception {
        createNewTopic("largetopic", 20);
        int countPerPartition = 50;
        int count = countPerPartition * partitions;
        Flux<ReceiverMessage<Integer, String>> kafkaFlux = createTestFlux(AckMode.AUTO_ACK).kafkaFlux();
        int threads = 4;
        Scheduler scheduler = Schedulers.newParallel("test-parallel", threads);
        AtomicBoolean firstMessage = new AtomicBoolean(true);
        Semaphore blocker = new Semaphore(0);

        kafkaFlux.take(count)
                 .parallel(4, 1)
                 .runOn(scheduler)
                 .subscribe(record -> {
                         if (firstMessage.compareAndSet(true, false))
                             blocker.acquireUninterruptibly();
                         onReceive(record.record());
                     });
        try {
            waitFoPartitionAssignment();
            sendMessages(0, count);
            Duration waitMs = Duration.ofMillis(receiveTimeoutMillis);
            // No ordering guarantees, but blocking of one thread should still allow messages to be
            // processed on other threads
            TestUtils.waitUntil("Messages not received", null, list -> count(list) >= count / 2, receivedMessages, waitMs);
            blocker.release();
            TestUtils.waitUntil("Messages not received", null, list -> count(list) == count, receivedMessages, waitMs);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    public void backPressureTest() throws Exception {
        int count = 10;
        receiverOptions.consumerProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
        Semaphore blocker = new Semaphore(0);
        Semaphore receiveSemaphore = new Semaphore(0);
        AtomicInteger receivedCount = new AtomicInteger();
        testSubscriber = TestSubscriber.create(1);
        Flux<ReceiverMessage<Integer, String>> kafkaFlux = createTestFlux(AckMode.AUTO_ACK).kafkaFlux()
                         .doOnNext(record -> {
                                 receivedCount.incrementAndGet();
                                 receiveSemaphore.release();
                                 try {
                                     blocker.acquire();
                                 } catch (InterruptedException e) {
                                     throw new RuntimeException(e);
                                 }
                                 testSubscriber.request(1);
                             })
                         .doOnError(e -> log.error("KafkaFlux exception", e));

        kafkaFlux.subscribe(testSubscriber);
        testSubscriber.assertSubscribed();
        waitFoPartitionAssignment();
        sendMessagesSync(0, count);

        TestUtils.sleep(2000); //
        assertTrue("Message not received", receiveSemaphore.tryAcquire(receiveTimeoutMillis, TimeUnit.MILLISECONDS));
        assertEquals(1, receivedCount.get());
        TestUtils.sleep(2000);
        long endTimeMillis = System.currentTimeMillis() + receiveTimeoutMillis;
        while (receivedCount.get() < count && System.currentTimeMillis() < endTimeMillis) {
            blocker.release();
            assertTrue("Message not received " + receivedCount, receiveSemaphore.tryAcquire(requestTimeoutMillis, TimeUnit.MILLISECONDS));
            Thread.sleep(10);
        }
    }

    @Test
    public final void messageProcessingFailureTest() throws Exception {
        int count = 200;
        int successfulReceives = 100;
        CountDownLatch receiveLatch = new CountDownLatch(successfulReceives + 1);
        Flux<ReceiverMessage<Integer, String>> kafkaFlux =
                Receiver.create(receiverOptions.ackMode(AckMode.MANUAL_ACK))
                        .doOnPartitionsAssigned(this::onPartitionsAssigned)
                        .receive(Collections.singletonList(topic))
                        .publishOn(Schedulers.single())
                        .doOnNext(record -> {
                                receiveLatch.countDown();
                                if (receiveLatch.getCount() == 0)
                                    throw new RuntimeException("Test exception");
                                record.offset().acknowledge();
                            });

        CountDownLatch latch = new CountDownLatch(successfulReceives);
        subscribe(kafkaFlux, latch);
        sendMessages(0, count);
        waitForMessages(latch);
        TestUtils.sleep(100);
        assertEquals(successfulReceives, count(receivedMessages));
    }

    @Ignore //  RS: FIXME Need to handle subscription after a cancel for the record emitter processor
    @Test
    public final void messageProcessingRetryTest() throws Exception {
        int count = 300;
        CountDownLatch receiveLatch = new CountDownLatch(count);
        testSubscriber = TestSubscriber.create(1);
        receiverOptions = receiverOptions.ackMode(AckMode.MANUAL_ACK)
                                         .consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Flux<ReceiverMessage<Integer, String>> kafkaFlux =
                Receiver.create(receiverOptions)
                        .doOnPartitionsAssigned(this::onPartitionsAssigned)
                        .receive(Collections.singletonList(topic))
                        .doOnNext(record -> {
                                receiveLatch.countDown();
                                onReceive(record.record());
                                if (receiveLatch.getCount() % 100 == 0)
                                    throw new RuntimeException("Test exception");
                                record.offset().acknowledge();
                                testSubscriber.request(1);
                            })
                        .doOnError(e -> log.error("KafkaFlux exception", e))
                        .retry(count / 100 + 1);

        kafkaFlux.subscribe(testSubscriber);
        testSubscriber.assertSubscribed();
        waitFoPartitionAssignment();
        sendMessages(0, count);
        waitForMessages(receiveLatch);
    }

    private Cancellation sendAndWaitForMessages(Flux<? extends ReceiverMessage<Integer, String>> kafkaFlux, int count) throws Exception {
        CountDownLatch receiveLatch = new CountDownLatch(count);
        Cancellation cancellation = subscribe(kafkaFlux, receiveLatch);
        sendMessages(0, count);
        waitForMessages(receiveLatch);
        return cancellation;
    }

    public TestableReceiver createTestFlux(AckMode ackMode) {
        if (ackMode != null) {
            receiverOptions = receiverOptions.ackMode(ackMode);
        }
        Receiver<Integer, String> receiver = Receiver.create(receiverOptions).doOnPartitionsAssigned(this::onPartitionsAssigned);
        Flux<ReceiverMessage<Integer, String>> kafkaFlux = receiver.receive(Collections.singletonList(topic));
        return new TestableReceiver(receiver, kafkaFlux);
    }

    private Cancellation subscribe(Flux<? extends ReceiverMessage<Integer, String>> kafkaFlux, CountDownLatch latch) throws Exception {
        Cancellation cancellation =
                kafkaFlux
                        .doOnNext(record -> {
                                onReceive(record.record());
                                latch.countDown();
                            })
                        .doOnError(e -> log.error("KafkaFlux exception", e))
                        .publishOn(consumerScheduler)
                        .subscribe();
        subscribeCancellations.add(cancellation);
        waitFoPartitionAssignment();
        return cancellation;
    }

    private void waitFoPartitionAssignment() throws InterruptedException {
        assertTrue("Partitions not assigned", assignSemaphore.tryAcquire(sessionTimeoutMillis + 1000, TimeUnit.MILLISECONDS));
    }

    private void waitForMessages(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(receiveTimeoutMillis, TimeUnit.MILLISECONDS))
            fail(latch.getCount() + " messages not received, received=" + count(receivedMessages) + " : " + receivedMessages);
    }

    private void waitForMessagesFromPartition(CountDownLatch latch, int partition) throws InterruptedException {
        if (!latch.await(receiveTimeoutMillis, TimeUnit.MILLISECONDS))
            fail(latch.getCount() + " messages not received, received=" + receivedMessages.get(partition).size() + " : " + receivedMessages.get(partition));
    }

    private void sendReceive(Flux<ReceiverMessage<Integer, String>> kafkaFlux,
            int sendStartIndex, int sendCount,
            int receiveStartIndex, int receiveCount) throws Exception {

        CountDownLatch latch = new CountDownLatch(receiveCount);
        subscribe(kafkaFlux, latch);
        if (sendCount > 0)
            sendMessages(sendStartIndex, sendCount);
        waitForMessages(latch);
        checkConsumedMessages(receiveStartIndex, receiveCount);
    }

    private void sendReceiveWithSendDelay(Flux<ReceiverMessage<Integer, String>> kafkaFlux,
            Duration sendDelay,
            int startIndex, int count) throws Exception {

        CountDownLatch latch = new CountDownLatch(count);
        subscribe(kafkaFlux, latch);
        Thread.sleep(sendDelay.toMillis());
        sendMessages(startIndex, count);
        waitForMessages(latch);
        checkConsumedMessages(startIndex, count);
    }

    private void sendReceiveWithRedelivery(Flux<ReceiverMessage<Integer, String>> kafkaFlux,
            int sendStartIndex, int sendCount, int minRedelivered, int maxRedelivered) throws Exception {

        int maybeRedelivered = maxRedelivered - minRedelivered;
        CountDownLatch latch = new CountDownLatch(sendCount + maxRedelivered);
        subscribe(kafkaFlux, latch);
        sendMessages(sendStartIndex, sendCount);

        // Countdown the latch manually for messages that may or may not be redelivered on each partition
        for (int i = 0; i < partitions; i++) {
            TestUtils.waitUntil("Messages not received on partition " + i, null, list -> list.size() > 0, receivedMessages.get(i), Duration.ofMillis(receiveTimeoutMillis));
        }
        int minReceiveIndex = sendStartIndex - minRedelivered;
        for (int i = minReceiveIndex - maybeRedelivered; i < minReceiveIndex; i++) {
            int partition = i % partitions;
            if (receivedMessages.get(partition).get(0) > i)
                latch.countDown();
        }

        // Wait for messages, redelivered as well as those sent here
        waitForMessages(latch);

        // Within the range including redelivered, check that all messages were delivered.
        for (int i = 0; i < partitions; i++) {
            List<Integer> received = receivedMessages.get(i);
            int receiveStartIndex = received.get(0);
            int receiveEndIndex = received.get(received.size() - 1);
            checkConsumedMessages(i, receiveStartIndex, receiveEndIndex);
        }
    }

    private void sendMessages(int startIndex, int count) throws Exception {
        Cancellation cancellation = kafkaSender.sendAll(Flux.range(startIndex, count)
                                                            .map(i -> createProducerRecord(i, true)))
                                               .subscribe();
        subscribeCancellations.add(cancellation);
    }

    private void sendMessagesSync(int startIndex, int count) throws Exception {
        CountDownLatch latch = new CountDownLatch(count);
        Flux.range(startIndex, count)
            .map(i -> createProducerRecord(i, true))
            .concatMap(record -> kafkaSender.sendAll(Mono.just(record))
                                            .doOnSuccess(metadata -> latch.countDown())
                                            .retry(100))
            .subscribe();
        assertTrue("Messages not sent ", latch.await(receiveTimeoutMillis, TimeUnit.MILLISECONDS));
    }

    private void sendMessagesToPartition(int partition, int startIndex, int count) throws Exception {
        Cancellation cancellation = kafkaSender
            .sendAll(Flux.range(startIndex, count)
                         .map(i -> {
                                 expectedMessages.get(partition).add(i);
                                 return new ProducerRecord<Integer, String>(topic, partition, i, "Message " + i);
                             }))
            .subscribe();
        subscribeCancellations.add(cancellation);
    }

    private void onPartitionsAssigned(Collection<ReceiverPartition> partitions) {
        assertEquals(topic, partitions.iterator().next().topicPartition().topic());
        assignSemaphore.release();
    }

    private void seekToBeginning(Collection<ReceiverPartition> partitions) {
        for (ReceiverPartition partition : partitions)
            partition.seekToBeginning();
        assertEquals(topic, partitions.iterator().next().topicPartition().topic());
        assignSemaphore.release();
    }

    private void onCommit(ReceiverMessage<?, ?> record, CountDownLatch commitLatch, long[] committedOffsets) {
        committedOffsets[record.record().partition()] = record.record().offset() + 1;
        commitLatch.countDown();
    }

    private void onCommit(List<ReceiverOffset> offsets, CountDownLatch commitLatch, long[] committedOffsets) {
        for (ReceiverOffset offset : offsets) {
            committedOffsets[offset.topicPartition().partition()] = offset.offset() + 1;
            commitLatch.countDown();
        }
        offsets.clear();
    }

    private void checkCommitCallbacks(CountDownLatch commitLatch, long[] committedOffsets) throws InterruptedException {
        assertTrue(commitLatch.getCount() + " commit callbacks not invoked", commitLatch.await(receiveTimeoutMillis, TimeUnit.MILLISECONDS));
        for (int i = 0; i < partitions; i++)
            assertEquals(committedOffsets[i], receivedMessages.get(i).size());
    }

    private void restartAndCheck(TestableReceiver testFlux,
            int sendStartIndex, int sendCount, int maxRedelivered) throws Exception {
        Thread.sleep(500); // Give a little time for commits to complete before terminating abruptly
        testFlux.terminate();
        cancelSubscriptions(true);
        clearReceivedMessages();
        Flux<ReceiverMessage<Integer, String>> kafkaFlux2 = createTestFlux(AckMode.ATMOST_ONCE).kafkaFlux();
        sendReceiveWithRedelivery(kafkaFlux2, sendStartIndex, sendCount, 0, maxRedelivered);
        clearReceivedMessages();
        cancelSubscriptions(false);
    }

    private void cancelSubscriptions(boolean failOnError) {
        try {
            for (Cancellation cancellation : subscribeCancellations)
                cancellation.dispose();
        } catch (Exception e) {
            // ignore since the scheduler was shutdown for the first consumer
            if (failOnError)
                throw e;
        }
        subscribeCancellations.clear();
    }
}
