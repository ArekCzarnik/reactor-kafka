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
package reactor.kafka.samples;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import reactor.core.Cancellation;
import reactor.core.publisher.Mono;
import reactor.kafka.AbstractKafkaTest;
import reactor.kafka.inbound.InboundRecord;
import reactor.kafka.inbound.Offset;
import reactor.kafka.inbound.InboundOptions;
import reactor.kafka.inbound.KafkaInbound;
import reactor.kafka.samples.SampleScenarios.AtmostOnce;
import reactor.kafka.samples.SampleScenarios.CommittableSource;
import reactor.kafka.samples.SampleScenarios.FanOut;
import reactor.kafka.samples.SampleScenarios.KafkaTransform;
import reactor.kafka.samples.SampleScenarios.PartitionProcessor;
import reactor.kafka.samples.SampleScenarios.KafkaSink;
import reactor.kafka.samples.SampleScenarios.KafkaSource;
import reactor.kafka.samples.SampleScenarios.Person;
import reactor.kafka.util.TestUtils;

public class SampleScenariosTest extends AbstractKafkaTest {

    private String bootstrapServers;
    private List<Cancellation> cancellations = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        super.setUp();
        bootstrapServers = embeddedKafka.getBrokersAsString();
    }

    @After
    public void tearDown() {
        for (Cancellation cancellation : cancellations)
            cancellation.dispose();
    }

    @Test
    public void kafkaSinkTest() throws Exception {
        List<Person> expected = new ArrayList<>();
        List<Person> received = new ArrayList<>();
        subscribeToDestTopic(topic, received);
        KafkaSink sink = new KafkaSink(bootstrapServers, topic);
        sink.source(createTestSource(10, expected));
        sink.runScenario();
        waitForMessages(expected, received);
    }

    @Test
    public void kafkaSourceTest() throws Exception {
        List<Person> expected = new ArrayList<>();
        List<Person> received = new ArrayList<>();
        KafkaSource source = new KafkaSource(bootstrapServers, topic) {
            public Mono<Void> storeInDB(Person person) {
                received.add(person);
                return Mono.empty();
            }
            public InboundOptions<Integer, Person> inboundOptions() {
                return super.inboundOptions().consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            }
        };
        cancellations.add(source.flux().subscribe());
        sendMessages(topic, 20, expected);
        waitForMessages(expected, received);
    }

    @Test
    public void kafkaTransformTest() throws Exception {
        List<Person> expected = new ArrayList<>();
        List<Person> received = new ArrayList<>();
        String sourceTopic = topic;
        String destTopic = "testtopic2";
        createNewTopic(destTopic, partitions);
        KafkaTransform flow = new KafkaTransform(bootstrapServers, sourceTopic, destTopic) {
            public InboundOptions<Integer, Person> inboundOptions() {
                return super.inboundOptions().consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            }
        };
        cancellations.add(flow.flux().subscribe());
        subscribeToDestTopic(destTopic, received);
        sendMessages(sourceTopic, 20, expected);
        for (Person p : expected)
            p.email(flow.transform(p).value().email());
        waitForMessages(expected, received);
    }

    @Test
    public void atmostOnceTest() throws Exception {
        List<Person> expected = new ArrayList<>();
        List<Person> received = new ArrayList<>();
        String sourceTopic = topic;
        String destTopic = "testtopic2";
        createNewTopic(destTopic, partitions);
        AtmostOnce flow = new AtmostOnce(bootstrapServers, sourceTopic, destTopic) {
            public InboundOptions<Integer, Person> inboundOptions() {
                return super.inboundOptions().consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            }
        };
        cancellations.add(flow.flux().subscribe());
        subscribeToDestTopic(destTopic, received);
        sendMessages(sourceTopic, 20, expected);
        for (Person p : expected)
            p.email(flow.transform(p).value().email());
        waitForMessages(expected, received);
    }

    @Test
    public void fanOutTest() throws Exception {
        List<Person> expected1 = new ArrayList<>();
        List<Person> expected2 = new ArrayList<>();
        List<Person> received1 = new ArrayList<>();
        List<Person> received2 = new ArrayList<>();
        String sourceTopic = topic;
        String destTopic1 = "testtopic1";
        String destTopic2 = "testtopic2";
        createNewTopic(destTopic1, partitions);
        createNewTopic(destTopic2, partitions);
        FanOut flow = new FanOut(bootstrapServers, sourceTopic, destTopic1, destTopic2) {
            public InboundOptions<Integer, Person> inboundOptions() {
                return super.inboundOptions().consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            }
        };
        cancellations.add(flow.flux().subscribe());
        subscribeToDestTopic(destTopic1, received1);
        subscribeToDestTopic(destTopic2, received2);
        sendMessages(sourceTopic, 20, expected1);
        for (Person p : expected1) {
            Person p2 = new Person(p.id(), p.firstName(), p.lastName());
            p2.email(flow.process2(p).value().email());
            expected2.add(p2);
            p.email(flow.process1(p).value().email());
        }
        waitForMessages(expected1, received1);
        waitForMessages(expected2, received2);
    }

    @Test
    public void partitionTest() throws Exception {
        List<Person> expected = new ArrayList<>();
        List<Person> received = new ArrayList<>();
        Map<Integer, List<Person>> partitionMap = new HashMap<>();
        for (int i = 0; i < partitions; i++)
            partitionMap.put(i, new ArrayList<>());
        PartitionProcessor source = new PartitionProcessor(bootstrapServers, topic) {
            public InboundOptions<Integer, Person> inboundOptions() {
                return super.inboundOptions().consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            }
            @Override
            public Offset processRecord(TopicPartition topicPartition, InboundRecord<Integer, Person> message) {
                Person person = message.record().value();
                received.add(person);
                partitionMap.get(message.record().partition()).add(person);
                return super.processRecord(topicPartition, message);
            }

        };
        cancellations.add(source.flux().subscribe());
        sendMessages(topic, 1000, expected);
        waitForMessages(expected, received);
        checkMessageOrder(partitionMap);
    }

    private void subscribeToDestTopic(String topic, List<Person> received) {
        KafkaSource source = new KafkaSource(bootstrapServers, topic);
        InboundOptions<Integer, Person> inboundOptions = source.inboundOptions()
                .consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                .consumerProperty(ConsumerConfig.GROUP_ID_CONFIG, "test-group");
        Cancellation c = KafkaInbound.create(inboundOptions.subscription(Collections.singleton(topic)))
                                 .receive()
                                 .subscribe(m -> received.add(m.record().value()));
        cancellations.add(c);
    }
    private CommittableSource createTestSource(int count, List<Person> expected) {
        for (int i = 0; i < count; i++)
            expected.add(new Person(i, "foo" + i, "bar" + i));

        return new CommittableSource(expected);
    }
    private void sendMessages(String topic, int count, List<Person> expected) throws Exception {
        KafkaSink sink = new KafkaSink(bootstrapServers, topic);
        sink.source(createTestSource(count, expected));
        sink.runScenario();
    }
    private void waitForMessages(List<Person> expected, List<Person> received) throws Exception {
        TestUtils.waitUntil("One or more messages not received, received=", () -> received.size(), r -> r.size() == expected.size(), received, Duration.ofMillis(receiveTimeoutMillis));
        assertEquals(new HashSet<>(expected), new HashSet<>(received));
    }
    private void checkMessageOrder(Map<Integer, List<Person>> received) throws Exception {
        for (List<Person> list : received.values()) {
            for (int i = 1; i < list.size(); i++) {
                assertTrue("Received out of order =: " + received, list.get(i - 1).id() < list.get(i).id());
            }
        }
    }
}
