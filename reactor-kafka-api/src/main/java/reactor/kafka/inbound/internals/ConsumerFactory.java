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

import java.time.Duration;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.config.ConfigException;

import reactor.kafka.inbound.InboundOptions;

public class ConsumerFactory {

    public static final ConsumerFactory INSTANCE = new ConsumerFactory();

    private ConsumerFactory() {
    }

    public <K, V> KafkaConsumer<K, V> createConsumer(InboundOptions<K, V> config) {
        return new KafkaConsumer<>(config.consumerProperties());
    }

    public String groupId(InboundOptions<?, ?> receiverOptions) {
        return (String) receiverOptions.consumerProperty(ConsumerConfig.GROUP_ID_CONFIG);
    }

    public Duration heartbeatInterval(InboundOptions<?, ?> receiverOptions) {
        Object value = receiverOptions.consumerProperty(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG);
        long heartbeatIntervalMs = 0;
        if (value != null) {
            if (value instanceof Long)
                heartbeatIntervalMs = (Long) value;
            else if (value instanceof String)
                heartbeatIntervalMs = Long.parseLong((String) value);
            else
                throw new ConfigException("Invalid heartbeat interval " + value);
        } else
            heartbeatIntervalMs = 3000; // Kafka default
        return Duration.ofMillis(heartbeatIntervalMs);
    }

    public Duration defaultAutoCommitInterval() {
        return Duration.ofMillis(5000); // Kafka default
    }

}
