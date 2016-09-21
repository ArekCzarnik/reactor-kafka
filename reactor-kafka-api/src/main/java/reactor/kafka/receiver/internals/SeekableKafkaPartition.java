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

import java.util.Collections;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

import reactor.kafka.receiver.ReceiverPartition;

public class SeekableKafkaPartition implements ReceiverPartition {

    private final KafkaConsumer<?, ?> consumer;
    private final TopicPartition topicPartition;

    public SeekableKafkaPartition(KafkaConsumer<?, ?> consumer, TopicPartition topicPartition) {
        this.consumer = consumer;
        this.topicPartition = topicPartition;
    }

    @Override
    public TopicPartition topicPartition() {
        return topicPartition;
    }

    @Override
    public void seekToBeginning() {
        this.consumer.seekToBeginning(Collections.singletonList(topicPartition));
    }

    @Override
    public void seekToEnd() {
        this.consumer.seekToEnd(Collections.singletonList(topicPartition));
    }

    @Override
    public void seek(long offset) {
        this.consumer.seek(topicPartition, offset);
    }

    @Override
    public long position() {
        return this.consumer.position(topicPartition);
    }

    @Override
    public String toString() {
        return String.valueOf(topicPartition);
    }
}