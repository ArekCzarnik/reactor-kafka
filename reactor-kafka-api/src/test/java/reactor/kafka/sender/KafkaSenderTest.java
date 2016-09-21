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
package reactor.kafka.sender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import reactor.core.publisher.BlockingSink;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.AbstractKafkaTest;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.internals.ConsumerFactory;
import reactor.kafka.sender.internals.KafkaSender;
import reactor.kafka.util.TestUtils;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

public class KafkaSenderTest extends AbstractKafkaTest {

    private static final Logger log = LoggerFactory.getLogger(KafkaSenderTest.class.getName());

    private KafkaSender<Integer, String> kafkaSender;
    private Consumer<Integer, String> consumer;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        kafkaSender = new KafkaSender<Integer, String>(senderOptions);
        consumer = createConsumer();
    }

    @After
    public void tearDown() {
        if (consumer != null)
            consumer.close();
        if (kafkaSender != null)
            kafkaSender.close();
    }

    @Test
    public void sendAll() throws Exception {
        int count = 1000;
        Flux<Integer> source = Flux.range(0, count);
        kafkaSender.sendAll(source.map(i -> createProducerRecord(i, true)))
                   .subscribe()
                   .block();

        waitForMessages(consumer, count, true);
    }

    @Test
    public void sendAllFailOnErrorTest() throws Exception {
        int count = 4;
        Semaphore errorSemaphore = new Semaphore(0);
        try {
            kafkaSender.sendAll(createOutboundErrorFlux(count, true, false))
                       .doOnError(t -> errorSemaphore.release())
                .subscribe();
        } catch (Exception e) {
            // ignore
            assertTrue("Invalid exception " + e, e.getClass().getName().contains("CancelException"));
        }
        waitForMessages(consumer, 1, true);
        assertTrue("Error callback not invoked", errorSemaphore.tryAcquire(requestTimeoutMillis, TimeUnit.MILLISECONDS));
    }

    @Test
    public void fluxFireAndForgetTest() throws Exception {
        int count = 1000;
        Flux<Integer> source = Flux.range(0, count);
        kafkaSender.send(source.map(i -> Tuples.of(createProducerRecord(i, true), null)), Schedulers.single(), 256, true)
                   .subscribe();

        waitForMessages(consumer, count, true);
    }

    @Test
    public void fluxPublishCallbackTest() throws Exception {
        int count = 10;
        CountDownLatch latch = new CountDownLatch(count);
        Semaphore completeSemaphore = new Semaphore(0);
        Flux<Integer> source = Flux.range(0, count);
        kafkaSender.send(source.map(i -> Tuples.of(createProducerRecord(i, true), latch)))
            .doOnNext(result -> result.getT2().countDown())
            .doOnComplete(() -> completeSemaphore.release())
            .subscribe();

        assertTrue("Missing callbacks " + latch.getCount(), latch.await(receiveTimeoutMillis, TimeUnit.MILLISECONDS));
        assertTrue("Completion callback not invoked", completeSemaphore.tryAcquire(requestTimeoutMillis, TimeUnit.MILLISECONDS));
        waitForMessages(consumer, count, true);
    }

    @Test
    public void fluxPublishCallbackWithRequestMappingTest() throws Exception {
        int count = 10;
        Map<Integer, RecordMetadata> resultMap = new HashMap<>();
        Flux<Integer> source = Flux.range(0, count);
        kafkaSender.send(source.map(i -> Tuples.of(createProducerRecord(i, true), i)))
            .doOnNext(result -> resultMap.put(result.getT2(), result.getT1()))
            .subscribe();

        waitForMessages(consumer, count, true);
        assertEquals(count, resultMap.size());
        for (int i = 0; i < count; i++) {
            RecordMetadata metadata = resultMap.get(i);
            assertNotNull("Callback not invoked for " + i, metadata);
            assertEquals(i % partitions, metadata.partition());
            assertEquals(i / partitions, metadata.offset());
        }
    }

    @Test
    public void fluxFireAndForgetFailureTest() throws Exception {
        int count = 4;
        Semaphore errorSemaphore = new Semaphore(0);
        Scheduler scheduler = Schedulers.single();
        kafkaSender.send(createOutboundErrorFlux(count, false, false).map(r -> Tuples.of(r, null)), scheduler, 1, true)
                   .doOnError(t -> errorSemaphore.release())
                   .subscribe();
        waitForMessages(consumer, 2, true);
        assertTrue("Error callback not invoked", errorSemaphore.tryAcquire(requestTimeoutMillis, TimeUnit.MILLISECONDS));
    }

    @Test
    public void fluxFailOnErrorTest() throws Exception {
        int count = 4;
        Semaphore errorSemaphore = new Semaphore(0);
        try {
            kafkaSender.send(createOutboundErrorFlux(count, true, false).map(r -> Tuples.of(r, null)))
                       .doOnError(t -> errorSemaphore.release())
                .subscribe();
        } catch (Exception e) {
            // ignore
            assertTrue("Invalid exception " + e, e.getClass().getName().contains("CancelException"));
        }
        waitForMessages(consumer, 1, true);
        assertTrue("Error callback not invoked", errorSemaphore.tryAcquire(requestTimeoutMillis, TimeUnit.MILLISECONDS));
    }

    @Test
    public void fluxSendCallbackBlockTest() throws Exception {
        int count = 20;
        Semaphore blocker = new Semaphore(0);
        CountDownLatch sendLatch = new CountDownLatch(count);
        kafkaSender.send(Flux.range(0, count / 2).map(i -> Tuples.of(createProducerRecord(i, true), null)))
                   .doOnNext(r -> {
                           assertFalse("Running onNext on producer network thread", Thread.currentThread().getName().contains("network"));
                           sendLatch.countDown();
                           TestUtils.acquireSemaphore(blocker);
                       })
                   .subscribe();
        kafkaSender.send(Flux.range(count / 2, count / 2).map(i -> Tuples.of(createProducerRecord(i, true), null)))
                   .doOnError(e -> log.error("KafkaSender exception", e))
                   .doOnNext(r -> sendLatch.countDown())
                   .subscribe();
        waitForMessages(consumer, count, false);
        for (int i = 0; i < count / 2; i++)
            blocker.release();
        if (!sendLatch.await(receiveTimeoutMillis, TimeUnit.MILLISECONDS))
            fail(sendLatch.getCount() + " send callbacks not received");
    }

    @Test
    public void fluxRetryTest() throws Exception {
        int count = 4;
        Semaphore completeSemaphore = new Semaphore(0);
        AtomicInteger messageIndex = new AtomicInteger();
        AtomicInteger lastSuccessful = new AtomicInteger();
        kafkaSender.send(createOutboundErrorFlux(count, false, true).map(r -> Tuples.of(r, messageIndex.getAndIncrement())))
                   .doOnNext(r -> lastSuccessful.set(r.getT2()))
                   .onErrorResumeWith(e -> {
                           waitForTopic(topic, partitions, false);
                           TestUtils.sleep(2000);
                           int next = lastSuccessful.get() + 1;
                           return outboundFlux(next, count - next);
                       })
                   .doOnComplete(() -> completeSemaphore.release())
                   .subscribe();

        waitForMessages(consumer, count, false);
        assertTrue("Completion callback not invoked", completeSemaphore.tryAcquire(requestTimeoutMillis, TimeUnit.MILLISECONDS));
    }

    @Test
    public void concurrentFluxSendTest() throws Exception {
        int count = 1000;
        int fluxCount = 5;
        Scheduler scheduler = Schedulers.newParallel("send-test");
        CountDownLatch latch = new CountDownLatch(fluxCount + count);
        for (int i = 0; i < fluxCount; i++) {
            kafkaSender.send(Flux.range(0, count)
                                 .map(index -> Tuples.of(new ProducerRecord<>(topic, 0, "Message " + index), null))
                       .publishOn(scheduler)
                       .doOnNext(r -> latch.countDown()))
                       .subscribe();
        }

        assertTrue("Missing callbacks " + latch.getCount(), latch.await(receiveTimeoutMillis, TimeUnit.MILLISECONDS));
        waitForMessages(consumer, count * fluxCount, false);
        scheduler.shutdown();
    }

    @Test
    public void fluxBackPressureTest() throws Exception {
        kafkaSender.close();
        senderOptions = senderOptions.producerProperty(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, "100")
                                   .producerProperty(ProducerConfig.MAX_BLOCK_MS_CONFIG, "1000");
        kafkaSender = new KafkaSender<Integer, String>(senderOptions);

        int count = 100;
        int maxConcurrency = 4;
        AtomicInteger inflight = new AtomicInteger();
        AtomicInteger maxInflight = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(count);
        Flux<Tuple2<ProducerRecord<Integer, String>, Integer>> source =
                Flux.range(0, count)
                    .map(i -> {
                            int current = inflight.incrementAndGet();
                            if (current > maxInflight.get())
                                maxInflight.set(current);
                            return Tuples.of(createProducerRecord(i, true), null);
                        });
        kafkaSender.send(source, Schedulers.single(), maxConcurrency, false)
                   .doOnNext(metadata -> {
                           TestUtils.sleep(100);
                           latch.countDown();
                           inflight.decrementAndGet();
                       })
                   .subscribe();

        assertTrue("Missing callbacks " + latch.getCount(), latch.await(receiveTimeoutMillis, TimeUnit.MILLISECONDS));
        assertTrue("Too many messages in flight " + maxInflight, maxInflight.get() <= maxConcurrency);
        waitForMessages(consumer, count, true);
    }

    @Test
    public void emitterTest() throws Exception {
        int count = 5000;
        EmitterProcessor<Integer> emitter = EmitterProcessor.create();
        BlockingSink<Integer> sink = emitter.connectSink();
        List<List<Integer>> successfulSends = new ArrayList<>();
        Set<Integer> failedSends = new HashSet<>();
        Semaphore done = new Semaphore(0);
        Scheduler scheduler = Schedulers.newSingle("kafka-sender");
        int maxInflight = 1024;
        boolean delayError = true;
        for (int i = 0; i < partitions; i++)
            successfulSends.add(new ArrayList<>());
        kafkaSender.send(emitter.map(i -> Tuples.of(new ProducerRecord<Integer, String>(topic, i % partitions, i, "Message " + i), i)), scheduler, maxInflight, delayError)
                   .doOnNext(result -> {
                           int messageIdentifier = result.getT2();
                           RecordMetadata metadata = result.getT1();
                           if (metadata != null)
                               successfulSends.get(metadata.partition()).add(messageIdentifier);
                           else
                               failedSends.add(messageIdentifier);
                       })
                   .doOnComplete(() -> done.release())
                   .subscribe();
        for (int i = 0; i < count; i++) {
            sink.submit(i);
        }
        sink.complete();

        assertTrue("Send not complete", done.tryAcquire(receiveTimeoutMillis, TimeUnit.MILLISECONDS));
        waitForMessages(consumer, count, false);
        assertEquals(0, failedSends.size());
        // Check that responses corresponding to each partition are ordered
        for (List<Integer> list : successfulSends) {
            assertEquals(count / partitions, list.size());
            for (int i = 1; i < list.size(); i++) {
                assertEquals(list.get(i - 1) + partitions, (int) list.get(i));
            }
        }
    }

    private Consumer<Integer, String> createConsumer() throws Exception {
        String groupId = testName.getMethodName();
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(groupId, "true", embeddedKafka);
        Consumer<Integer, String> consumer = ConsumerFactory.INSTANCE.createConsumer(new ReceiverOptions<Integer, String>(consumerProps));
        consumer.subscribe(Collections.singletonList(topic));
        consumer.poll(requestTimeoutMillis);
        return consumer;
    }

    private void waitForMessages(Consumer<Integer, String> consumer, int expectedCount, boolean checkMessageOrder) {
        int receivedCount = 0;
        long endTimeMillis = System.currentTimeMillis() + receiveTimeoutMillis;
        while (receivedCount < expectedCount && System.currentTimeMillis() < endTimeMillis) {
            ConsumerRecords<Integer, String> records = consumer.poll(1000);
            records.forEach(record -> onReceive(record));
            receivedCount += records.count();
        }
        if (checkMessageOrder)
            checkConsumedMessages();
        assertEquals(expectedCount, receivedCount);
        ConsumerRecords<Integer, String> records = consumer.poll(500);
        assertTrue("Unexpected message received: " + records, records.isEmpty());
    }

    private Flux<ProducerRecord<Integer, String>> createOutboundErrorFlux(int count, boolean failOnError, boolean hasRetry) {
        return Flux.range(0, count)
                   .map(i ->
                       {
                           int failureIndex = 1;
                           int restartIndex = count - 1;
                           try {
                               if (i == failureIndex) {
                                   Thread.sleep(requestTimeoutMillis / 2); // give some time for previous messages to be sent
                                   shutdownKafkaBroker();
                               } else if (i == restartIndex) {
                                   Thread.sleep(requestTimeoutMillis);     // wait for previous request to timeout
                                   restartKafkaBroker();
                               }
                           } catch (Exception e) {
                               throw new RuntimeException(e);
                           }

                           boolean expectSuccess = hasRetry || i < failureIndex || (!failOnError && i >= restartIndex);
                           return createProducerRecord(i, expectSuccess);
                       });
    }

    private Flux<Tuple2<RecordMetadata, Integer>> outboundFlux(int startIndex, int count) {
        return kafkaSender.send(Flux.range(startIndex, count).map(i -> Tuples.of(createProducerRecord(i, true), i)));
    }

}
