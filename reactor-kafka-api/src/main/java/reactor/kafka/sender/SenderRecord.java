/*
 * Copyright (c) 2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.kafka.sender;

import org.apache.kafka.clients.producer.ProducerRecord;


/**
 * Represents an outgoing record. Along with the record to send to Kafka,
 * additional correlation metadata may also be specified to correlate
 * {@link SenderResult} to its corresponding record.
 *
 * @param <K> Outgoing record key type
 * @param <V> Outgoing record value type
 * @param <T> Correlation metadata type
 */
public class SenderRecord<K, V, T> {

    private final ProducerRecord<K, V> record;
    private final T correlationMetadata;

    /**
     * Creates a record to send to Kafka.
     *
     * @param record the producer record to send to Kafka
     * @param correlationMetadata Additional correlation metadata that is not sent to Kafka, but is
     *        included in the response to match {@link SenderResult} to this record.
     * @return new sender record that can be sent to Kafka using {@link Sender#send(org.reactivestreams.Publisher, boolean)}
     */
    public static <K, V, T> SenderRecord<K, V, T> create(ProducerRecord<K, V> record, T correlationMetadata) {
        return new SenderRecord<K, V, T>(record, correlationMetadata);
    }

    private SenderRecord(ProducerRecord<K, V> record, T correlationMetadata) {
        this.record = record;
        this.correlationMetadata = correlationMetadata;
    }

    /**
     * Returns the Kafka producer record associated with this instance.
     * @return record to send to Kafka
     */
    public ProducerRecord<K, V> record() {
        return record;
    }

    /**
     * Returns the correlation metadata associated with this instance which is not sent to Kafka,
     * but can be used to correlate response to outbound request.
     * @return metadata associated with sender record that is not sent to Kafka
     */
    public T correlationMetadata() {
        return correlationMetadata;
    }
}