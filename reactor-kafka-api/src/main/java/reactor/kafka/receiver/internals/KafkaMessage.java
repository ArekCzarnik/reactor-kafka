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
package reactor.kafka.receiver.internals;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import reactor.kafka.receiver.ReceiverMessage;
import reactor.kafka.receiver.ReceiverOffset;

public class KafkaMessage<K, V> implements ReceiverMessage<K, V> {

    private final ConsumerRecord<K, V> consumerRecord;
    private final ReceiverOffset consumerOffset;

    public KafkaMessage(ConsumerRecord<K, V> consumerRecord, ReceiverOffset consumerOffset) {
        this.consumerRecord = consumerRecord;
        this.consumerOffset = consumerOffset;
    }

    public ConsumerRecord<K, V> record() {
        return consumerRecord;
    }

    public ReceiverOffset offset() {
        return consumerOffset;
    }

    @Override
    public String toString() {
        return String.valueOf(consumerRecord);
    }
}