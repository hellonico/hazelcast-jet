/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet;

import com.hazelcast.jet.core.WatermarkGenerationParams;
import com.hazelcast.jet.core.processor.KafkaProcessors;
import com.hazelcast.jet.function.DistributedBiFunction;

import javax.annotation.Nonnull;
import java.util.Map.Entry;
import java.util.Properties;

/**
 * Contains factory methods for Apache Kafka sources.
 */
public final class KafkaSources {

    private KafkaSources() {
    }

    /**
     * Convenience for {@link #kafka(Properties, DistributedBiFunction,
     * WatermarkGenerationParams, String...)} wrapping the output in {@code
     * Map.Entry}.
     */
    @Nonnull
    public static <K, V> Source<Entry<K, V>> kafka(
            @Nonnull Properties properties,
            @Nonnull WatermarkGenerationParams<Entry<K, V>> wmGenParams,
            @Nonnull String... topics
    ) {
        return Sources.fromProcessor("streamKafka", KafkaProcessors.streamKafkaP(properties, wmGenParams, topics));
    }

    /**
     * Returns a source that consumes one or more Apache Kafka topics and emits
     * items from them as {@code Map.Entry} instances.
     * <p>
     * The source creates a {@code KafkaConsumer} for each {@link
     * com.hazelcast.jet.core.Processor processor} instance using the supplied
     * {@code properties}. It assigns a subset of Kafka partitions to each of
     * them using manual partition assignment (it ignores the {@code group.id}
     * property). Default local parallelism for this processor is 2 (or less
     * if less CPUs are available).
     * <p>
     * If snapshotting is enabled, partition offsets are saved to the snapshot.
     * After restart, the source emits the events from the same offset.
     * <p>
     * If snapshotting is disabled, the source commits the offsets to Kafka
     * using {@link org.apache.kafka.clients.consumer.KafkaConsumer#commitSync()
     * commitSync()}. Note however that offsets can be committed before or
     * after the event is fully processed.
     * <p>
     * If you add Kafka partitions at run-time, consumption from them will
     * start after a delay, based on the {@code metadata.max.age.ms} Kafka
     * property. Note, however, that events from them can be dropped as late if
     * the allowed lag is not enough.
     * <p>
     * The processor completes only in the case of an error or if the job is
     * cancelled. IO failures are generally handled by Kafka producer and
     * do not cause the processor to fail.
     * Kafka consumer also does not return from {@code poll(timeout)} if the
     * cluster is down. If {@link
     * com.hazelcast.jet.config.JobConfig#setSnapshotIntervalMillis(long)
     * snapshotting is enabled}, entire job might be blocked. This is a known
     * issue of Kafka (KAFKA-1894).
     * Refer to Kafka documentation for details.
     * <p>
     * Default local parallelism for this processor is 2 (or less if less CPUs
     * are available).
     *
     * @param properties consumer properties broker address and key/value deserializers
     * @param projectionFn function to create output objects from key and value.
     *                     If the projection returns a {@code null} for an item, that item
     *                     will be filtered out.
     * @param topics     the list of topics
     */
    @Nonnull
    public static <K, V, T> Source<T> kafka(
            @Nonnull Properties properties,
            @Nonnull DistributedBiFunction<K, V, T> projectionFn,
            @Nonnull WatermarkGenerationParams<T> wmGenParams,
            @Nonnull String... topics
    ) {
        return Sources.fromProcessor("streamKafka", KafkaProcessors.streamKafkaP(properties, projectionFn, wmGenParams,
                topics));
    }
}
