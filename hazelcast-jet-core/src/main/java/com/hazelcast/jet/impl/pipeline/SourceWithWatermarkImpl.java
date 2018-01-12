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

package com.hazelcast.jet.impl.pipeline;

import com.hazelcast.jet.pipeline.SourceWithWatermark;
import com.hazelcast.jet.core.WatermarkPolicy;
import com.hazelcast.jet.function.DistributedSupplier;
import com.hazelcast.jet.function.DistributedToLongFunction;

/**
 * Javadoc pending.
 */
public class SourceWithWatermarkImpl<T> implements SourceWithWatermark<T> {

    private final SourceImpl<T> source;
    private final DistributedToLongFunction<? super T> timestampFn;
    private final DistributedSupplier<WatermarkPolicy> wmPolicy;

    SourceWithWatermarkImpl(
            SourceImpl<T> source,
            DistributedToLongFunction<? super T> timestampFn,
            DistributedSupplier<WatermarkPolicy> wmPolicy
    ) {
        this.source = source;
        this.timestampFn = timestampFn;
        this.wmPolicy = wmPolicy;
    }

    @Override
    public String name() {
        return null;
    }

    @Override
    public SourceImpl<T> source() {
        return source;
    }

    @Override
    public DistributedToLongFunction<? super T> timestampFn() {
        return timestampFn;
    }

    @Override
    public DistributedSupplier<WatermarkPolicy> createWatermarkPolicyFn() {
        return wmPolicy;
    }
}
