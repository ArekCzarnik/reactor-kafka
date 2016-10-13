/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package reactor.kafka.samples;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.Cancellation;
import reactor.core.publisher.BlockingSink;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.inbound.AckMode;
import reactor.kafka.inbound.KafkaInbound;
import reactor.kafka.inbound.InboundRecord;
import reactor.kafka.inbound.Offset;
import reactor.kafka.inbound.InboundOptions;
import reactor.kafka.outbound.OutboundOptions;
import reactor.kafka.outbound.OutboundRecord;
import reactor.kafka.outbound.OutboundResponse;
import reactor.kafka.outbound.KafkaOutbound;

/**
 * Sample flows using Reactive API for Kafka.
 * To run a sample scenario:
 * <ol>
 *   <li> Start Zookeeper and Kafka server
 *   <li> Create Kafka topics {@link #TOPICS}
 *   <li> Update {@link #BOOTSTRAP_SERVERS} and {@link #TOPICS} if required
 *   <li> Run {@link SampleScenarios} {@link Scenario} as Java application (eg. {@link SampleScenarios} KAFKA_SINK)
 *        with all dependent jars in the CLASSPATH (eg. from IDE).
 *   <li> Shutdown Kafka server and Zookeeper when no longer required
 * </ol>
 */
public class SampleScenarios {

    private static final Logger log = LoggerFactory.getLogger(SampleScenarios.class.getName());

    private static final String BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String[] TOPICS = {
        "sample-topic1",
        "sample-topic2",
        "sample-topic3"
    };

    enum Scenario {
        KAFKA_SINK,
        KAFKA_SOURCE,
        KAFKA_TRANSFORM,
        ATMOST_ONCE,
        FAN_OUT,
        PARTITION
    }

    /**
     * This sample demonstrates the use of Kafka as a sink when messages are transferred from
     * an external source to a Kafka topic. Unlimited (very large) blocking time and retries
     * are used to handle broker failures. Source records are committed when sends succeed.
     *
     */
    public static class KafkaSink extends AbstractScenario {
        private final String topic;

        public KafkaSink(String bootstrapServers, String topic) {
            super(bootstrapServers);
            this.topic = topic;
        }
        public Flux<?> flux() {
            OutboundOptions<Integer, Person> outboundOptions = outboundOptions()
                    .producerProperty(ProducerConfig.ACKS_CONFIG, "all")
                    .producerProperty(ProducerConfig.MAX_BLOCK_MS_CONFIG, Long.MAX_VALUE)
                    .producerProperty(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
            Flux<Person> srcFlux = source().flux();
            return outbound(outboundOptions)
                    .sendAll(srcFlux.map(p -> OutboundRecord.create(new ProducerRecord<>(topic, p.id(), p), p.id())))
                    .doOnError(e-> log.error("Send failed, terminating.", e))
                    .doOnNext(r -> {
                            int id = r.correlationMetadata();
                            log.info("Successfully stored person with id {} in Kafka", id);
                            source.commit(id);
                        });
        }
    }

    /**
     * This sample demonstrates the use of Kafka as a source when messages are transferred from
     * a Kafka topic to an external sink. Kafka offsets are committed when records are successfully
     * transferred. Unlimited retries on the source KafkaFlux ensure that the Kafka consumer is
     * restarted if there are any exceptions while processing records.
     */
    public static class KafkaSource extends AbstractScenario {
        private final String topic;

        public KafkaSource(String bootstrapServers, String topic) {
            super(bootstrapServers);
            this.topic = topic;
        }
        public Flux<?> flux() {
            return KafkaInbound.create(inboundOptions(AckMode.MANUAL_COMMIT, Collections.singletonList(topic)))
                           .receive()
                           .publishOn(Schedulers.newSingle("sample", true))
                           .flatMap(m -> storeInDB(m.record().value())
                                          .doOnSuccess(r -> m.offset().commit().block()))
                           .retry();
        }
        public Mono<Void> storeInDB(Person person) {
            log.info("Successfully processed person with id {} from Kafka", person.id());
            return Mono.empty();
        }
    }

    /**
     * This sample demonstrates a flow where messages are consumed from a Kafka topic, transformed
     * and the results stored in another Kafka topic. Manual acknowledgement ensures that offsets from
     * the source are committed only after they have been transferred to the destination. Acknowledged
     * offsets are committed periodically.
     */
    public static class KafkaTransform extends AbstractScenario {
        private final String sourceTopic;
        private final String destTopic;

        public KafkaTransform(String bootstrapServers, String sourceTopic, String destTopic) {
            super(bootstrapServers);
            this.sourceTopic = sourceTopic;
            this.destTopic = destTopic;
        }
        public Flux<?> flux() {
            KafkaOutbound<Integer, Person> outbound = outbound(outboundOptions());
            return outbound.sendAll(KafkaInbound.create(inboundOptions(AckMode.MANUAL_ACK, Collections.singleton(sourceTopic)))
                                       .receive()
                                       .map(m -> OutboundRecord.create(transform(m.record().value()), m.offset())))
                         .doOnNext(m -> m.correlationMetadata().acknowledge());
        }
        public ProducerRecord<Integer, Person> transform(Person p) {
            Person transformed = new Person(p.id(), p.firstName(), p.lastName());
            transformed.email(p.firstName().toLowerCase(Locale.ROOT) + "@kafka.io");
            return new ProducerRecord<>(destTopic, p.id(), transformed);
        }
    }

    /**
     * This sample demonstrates a flow with at-most once delivery. A topic with replication factor one
     * combined with a producer with acks=0 and no retries ensures that messages that could not be sent
     * to Kafka on the first attempt are dropped. On the consumer side, {@link KafkaFlux#atmostOnce()}
     * commits offsets before delivery to the application to ensure that if the consumer restarts,
     * messages are not redelivered.
     */
    public static class AtmostOnce extends AbstractScenario {
        private final String sourceTopic;
        private final String destTopic;

        public AtmostOnce(String bootstrapServers, String sourceTopic, String destTopic) {
            super(bootstrapServers);
            this.sourceTopic = sourceTopic;
            this.destTopic = destTopic;
        }
        public Flux<?> flux() {
            OutboundOptions<Integer, Person> outboundOptions = outboundOptions()
                    .producerProperty(ProducerConfig.ACKS_CONFIG, "0")
                    .producerProperty(ProducerConfig.RETRIES_CONFIG, "0");
            return outbound(outboundOptions)
                .sendAll(KafkaInbound.create(inboundOptions(AckMode.ATMOST_ONCE, Collections.singleton(sourceTopic)))
                              .receive()
                              .map(cr -> OutboundRecord.create(transform(cr.record().value()), cr.offset())),
                      Schedulers.single(),
                      256,
                      true);
        }
        public ProducerRecord<Integer, Person> transform(Person p) {
            Person transformed = new Person(p.id(), p.firstName(), p.lastName());
            transformed.email(p.firstName().toLowerCase(Locale.ROOT) + "@kafka.io");
            return new ProducerRecord<>(destTopic, p.id(), transformed);
        }
    }

    /**
     * This sample demonstrates a flow where messages are consumed from a Kafka topic and processed
     * by multiple streams with each transformed stream of messages stored in a separate Kafka topic.
     *
     */
    public static class FanOut extends AbstractScenario {
        private final String sourceTopic;
        private final String destTopic1;
        private final String destTopic2;

        public FanOut(String bootstrapServers, String sourceTopic, String destTopic1, String destTopic2) {
            super(bootstrapServers);
            this.sourceTopic = sourceTopic;
            this.destTopic1 = destTopic1;
            this.destTopic2 = destTopic2;
        }
        public Flux<?> flux() {
            Scheduler scheduler1 = Schedulers.newSingle("sample1", true);
            Scheduler scheduler2 = Schedulers.newSingle("sample2", true);
            outbound = outbound(outboundOptions());
            EmitterProcessor<Person> processor = EmitterProcessor.create();
            BlockingSink<Person> incoming = processor.connectSink();
            Flux<?> inFlux = KafkaInbound.create(inboundOptions(AckMode.AUTO_ACK, Collections.singleton(sourceTopic)))
                                     .receive()
                                     .doOnNext(m -> incoming.emit(m.record().value()));
            Flux<OutboundResponse<Integer>> stream1 = outbound.sendAll(processor.publishOn(scheduler1).map(p -> OutboundRecord.create(process1(p), p.id())));
            Flux<OutboundResponse<Integer>> stream2 = outbound.sendAll(processor.publishOn(scheduler2).map(p -> OutboundRecord.create(process2(p), p.id())));
            return Flux.merge(stream1, stream2)
                       .doOnSubscribe(s -> inFlux.subscribe());
        }
        public ProducerRecord<Integer, Person> process1(Person p) {
            log.debug("Processing person {} on stream1 in thread {}", p.id(), Thread.currentThread().getName());
            Person transformed = new Person(p.id(), p.firstName(), p.lastName());
            transformed.email(p.firstName().toLowerCase(Locale.ROOT) + "@kafka.io");
            return new ProducerRecord<>(destTopic1, p.id(), transformed);
        }
        public ProducerRecord<Integer, Person> process2(Person p) {
            log.debug("Processing person {} on stream2 in thread {}", p.id(), Thread.currentThread().getName());
            Person transformed = new Person(p.id(), p.firstName(), p.lastName());
            transformed.email(p.lastName().toLowerCase(Locale.ROOT) + "@reactor.io");
            return new ProducerRecord<>(destTopic2, p.id(), transformed);
        }
    }

    /**
     * This sample demonstrates a flow where messages are consumed from a Kafka topic, processed
     * by multiple threads and the results stored in another Kafka topic. Messages are grouped
     * by partition to guarantee ordering in message processing and commit operations. Messages
     * from each partition are processed on a single thread.
     */
    public static class PartitionProcessor extends AbstractScenario {
        private final String topic;

        public PartitionProcessor(String bootstrapServers, String topic) {
            super(bootstrapServers);
            this.topic = topic;
        }
        public Flux<?> flux() {
            Scheduler scheduler = Schedulers.newElastic("sample", 60, true);
            return KafkaInbound.create(inboundOptions(AckMode.MANUAL_COMMIT, Collections.singleton(topic)))
                            .receive()
                            .groupBy(m -> m.offset().topicPartition())
                            .flatMap(partitionFlux -> partitionFlux.publishOn(scheduler)
                                                                   .map(r -> processRecord(partitionFlux.key(), r))
                                                                   .sample(Duration.ofMillis(5000))
                                                                   .concatMap(offset -> offset.commit()));
        }
        public Offset processRecord(TopicPartition topicPartition, InboundRecord<Integer, Person> message) {
            log.info("Processing record {} from partition {} in thread{}",
                    message.record().value().id(), topicPartition, Thread.currentThread().getName());
            return message.offset();
        }
    }

    public static class Person {
        private final int id;
        private final String firstName;
        private final String lastName;
        private String email;
        public Person(int id, String firstName, String lastName) {
            this.id = id;
            this.firstName = firstName;
            this.lastName = lastName;
        }
        public int id() {
            return id;
        }
        public String firstName() {
            return firstName;
        }
        public String lastName() {
            return lastName;
        }
        public void email(String email) {
            this.email = email;
        }
        public String email() {
            return email == null ? "" : email;
        }
        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Person))
                return false;

            Person p = (Person) other;

            if (id != p.id)
                return false;
            return stringEquals(firstName, p.firstName) &&
                   stringEquals(lastName, p.lastName) &&
                   stringEquals(email, p.email);
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(id);
            result = 31 * result + (firstName != null ? firstName.hashCode() : 0);
            result = 31 * result + (lastName != null ? lastName.hashCode() : 0);
            return result;
        }
        public String toString() {
            return "Person{" +
                    "id='" + id + '\'' +
                    ", firstName='" + firstName + '\'' +
                    ", lastName='" + lastName + '\'' +
                    '}';
        }
        private boolean stringEquals(String str1, String str2) {
            return str1 == null ? str2 == null : str1.equals(str2);
        }
    }

    public static class PersonSerDes implements Serializer<Person>, Deserializer<Person> {

        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
        }

        @Override
        public byte[] serialize(String topic, Person person) {
            byte[] firstName = person.firstName().getBytes(StandardCharsets.UTF_8);
            byte[] lastName = person.lastName().getBytes(StandardCharsets.UTF_8);
            byte[] email = person.email().getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + firstName.length + 4 + lastName.length + 4 + email.length);
            buffer.putInt(person.id());
            buffer.putInt(firstName.length);
            buffer.put(firstName);
            buffer.putInt(lastName.length);
            buffer.put(lastName);
            buffer.putInt(email.length);
            buffer.put(email);
            return buffer.array();
        }

        @Override
        public Person deserialize(String topic, byte[] data) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            int id = buffer.getInt();
            byte[] first = new byte[buffer.getInt()];
            buffer.get(first);
            String firstName = new String(first, StandardCharsets.UTF_8);
            byte[] last = new byte[buffer.getInt()];
            buffer.get(last);
            String lastName = new String(last, StandardCharsets.UTF_8);
            Person person = new Person(id, firstName, lastName);
            byte[] email = new byte[buffer.getInt()];
            if (email.length > 0) {
                buffer.get(email);
                person.email(new String(email, StandardCharsets.UTF_8));
            }
            return person;
        }

        @Override
        public void close() {
        }
    }

    static class CommittableSource {
        private List<Person> sourceList = new ArrayList<>();
        CommittableSource() {
            sourceList.add(new Person(1, "John", "Doe"));
            sourceList.add(new Person(1, "Ada", "Lovelace"));
        }
        CommittableSource(List<Person> list) {
            sourceList.addAll(list);
        }
        Flux<Person> flux() {
            return Flux.fromIterable(sourceList);
        }

        void commit(int id) {
            log.trace("Committing {}", id);
        }
    }

    static abstract class AbstractScenario {
        String bootstrapServers = BOOTSTRAP_SERVERS;
        CommittableSource source;
        KafkaOutbound<Integer, Person> outbound;
        List<Cancellation> cancellations = new ArrayList<>();

        AbstractScenario(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }
        public abstract Flux<?> flux();

        public void runScenario() throws InterruptedException {
            flux().blockLast();
            close();
        }

        public void close() {
            if (outbound != null)
                outbound.close();
            for (Cancellation cancellation : cancellations)
                cancellation.dispose();
        }

        public OutboundOptions<Integer, Person> outboundOptions() {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.CLIENT_ID_CONFIG, "sample-producer");
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, IntegerSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, PersonSerDes.class);
            return OutboundOptions.create(props);
        }

        public KafkaOutbound<Integer, Person> outbound(OutboundOptions<Integer, Person> outboundOptions) {
            return KafkaOutbound.create(outboundOptions);
        }

        public InboundOptions<Integer, Person> inboundOptions() {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "sample-group");
            props.put(ConsumerConfig.CLIENT_ID_CONFIG, "sample-consumer");
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, IntegerDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, PersonSerDes.class);
            return InboundOptions.<Integer, Person>create(props);
        }

        public InboundOptions<Integer, Person> inboundOptions(AckMode ackMode, Collection<String> topics) {
            return inboundOptions()
                    .addAssignListener(p -> log.info("Partitions assigned {}", p))
                    .ackMode(ackMode)
                    .subscription(topics);
        }

        public void source(CommittableSource source) {
            this.source = source;
        }

        public CommittableSource source() {
            return source;
        }
    }

    public static void main(String[] args) throws Exception {

        if (args.length != 1) {
            System.out.println("Usage: " + SampleScenarios.class.getName() + " <scenario>");
            System.exit(1);
        }
        Scenario scenario = Scenario.valueOf(args[0]);
        AbstractScenario sampleScenario;
        switch (scenario) {
            case KAFKA_SINK:
                sampleScenario = new KafkaSink(BOOTSTRAP_SERVERS, TOPICS[0]);
                break;
            case KAFKA_SOURCE:
                sampleScenario = new KafkaSource(BOOTSTRAP_SERVERS, TOPICS[0]);
                break;
            case KAFKA_TRANSFORM:
                sampleScenario = new KafkaTransform(BOOTSTRAP_SERVERS, TOPICS[0], TOPICS[1]);
                break;
            case ATMOST_ONCE:
                sampleScenario = new AtmostOnce(BOOTSTRAP_SERVERS, TOPICS[0], TOPICS[1]);
                break;
            case FAN_OUT:
                sampleScenario = new FanOut(BOOTSTRAP_SERVERS, TOPICS[0], TOPICS[1], TOPICS[2]);
                break;
            case PARTITION:
                sampleScenario = new PartitionProcessor(BOOTSTRAP_SERVERS, TOPICS[0]);
                break;
            default:
                throw new IllegalArgumentException("Unsupported scenario " + scenario);
        }
        sampleScenario.runScenario();
    }
}
