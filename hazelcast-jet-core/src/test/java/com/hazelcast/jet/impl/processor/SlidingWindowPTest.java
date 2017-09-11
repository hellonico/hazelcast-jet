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

package com.hazelcast.jet.impl.processor;

import com.hazelcast.jet.AggregateOperation;
import com.hazelcast.jet.Processor;
import com.hazelcast.jet.TimestampKind;
import com.hazelcast.jet.TimestampedEntry;
import com.hazelcast.jet.Watermark;
import com.hazelcast.jet.WindowDefinition;
import com.hazelcast.jet.accumulator.LongAccumulator;
import com.hazelcast.jet.function.DistributedSupplier;
import com.hazelcast.test.HazelcastParametersRunnerFactory;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static com.hazelcast.jet.Util.entry;
import static com.hazelcast.jet.WindowDefinition.slidingWindowDef;
import static com.hazelcast.jet.processor.Processors.aggregateToSlidingWindow;
import static com.hazelcast.jet.processor.Processors.combineToSlidingWindow;
import static com.hazelcast.jet.test.TestSupport.testProcessor;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.shuffle;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
@Category({QuickTest.class, ParallelTest.class})
@Parameterized.UseParametersRunnerFactory(HazelcastParametersRunnerFactory.class)
public class SlidingWindowPTest {

    private static final Long KEY = 77L;

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Parameter
    public boolean hasDeduct;

    @Parameter(1)
    public boolean singleStageProcessor;

    private DistributedSupplier<Processor> supplier;
    private SlidingWindowP<?, ?, Long> lastSuppliedProcessor;

    @Parameters(name = "hasDeduct={0}, singleStageProcessor={1}")
    public static Collection<Object[]> parameters() {
        return IntStream.range(0, 4)
                        .mapToObj(i -> new Boolean[]{(i & 2) == 2, (i & 1) == 1})
                        .collect(toList());
    }

    @Before
    public void before() {
        WindowDefinition windowDef = slidingWindowDef(4, 1);
        AggregateOperation<Entry<?, Long>, LongAccumulator, Long> operation;

        operation = AggregateOperation.of(
                    LongAccumulator::new,
                    (acc, item) -> acc.addExact(item.getValue()),
                    LongAccumulator::addExact,
                    hasDeduct ? LongAccumulator::subtractExact : null,
                    LongAccumulator::get);

        DistributedSupplier<Processor> procSupplier = singleStageProcessor
                ? aggregateToSlidingWindow(
                            t -> KEY,
                            Entry<Long, Long>::getKey,
                            TimestampKind.EVENT,
                            windowDef,
                            operation)
                : combineToSlidingWindow(windowDef, operation);

        // new supplier to save the last supplied instance
        supplier = () -> lastSuppliedProcessor = (SlidingWindowP<?, ?, Long>) procSupplier.get();
    }

    @After
    public void after() {
        assertTrue("tsToKeyToFrame is not empty: " + lastSuppliedProcessor.tsToKeyToAcc,
                lastSuppliedProcessor.tsToKeyToAcc.isEmpty());
        assertTrue("slidingWindow is not empty: " + lastSuppliedProcessor.slidingWindow,
                lastSuppliedProcessor.slidingWindow == null || lastSuppliedProcessor.slidingWindow.isEmpty());
    }

    @Test
    public void when_noFramesReceived_then_onlyEmitWm() {
        List<Watermark> wmList = singletonList(wm(1));
        testProcessor(supplier, wmList, wmList, true, true, false, false, Objects::equals);
    }

    @Test
    public void simple_smokeTest() {
        testProcessor(supplier,
                asList(
                        event(0, 1),
                        wm(3)),
                asList(
                        outboxFrame(3, 1),
                        wm(3)
                ), true, true, false, false, Objects::equals);
    }

    @Test
    public void when_receiveAscendingTimestamps_then_emitAscending() {
        testProcessor(supplier,
                asList(
                        event(0, 1),
                        event(1, 1),
                        event(2, 1),
                        event(3, 1),
                        event(4, 1),
                        wm(0),
                        wm(1),
                        wm(2),
                        wm(3),
                        wm(4),
                        wm(5),
                        wm(6),
                        wm(7)
                ), asList(
                        outboxFrame(0, 1),
                        wm(0),
                        outboxFrame(1, 2),
                        wm(1),
                        outboxFrame(2, 3),
                        wm(2),
                        outboxFrame(3, 4),
                        wm(3),
                        outboxFrame(4, 4),
                        wm(4),
                        outboxFrame(5, 3),
                        wm(5),
                        outboxFrame(6, 2),
                        wm(6),
                        outboxFrame(7, 1),
                        wm(7)
                ), true, true, true, false, Objects::equals);
    }

    @Test
    public void when_receiveDescendingTimestamps_then_emitAscending() {
        testProcessor(supplier,
                asList(
                        event(4, 1),
                        event(3, 1),
                        event(2, 1),
                        event(1, 1),
                        event(0, 1),
                        wm(0),
                        wm(1),
                        wm(2),
                        wm(3),
                        wm(4),
                        wm(5),
                        wm(6),
                        wm(7)
                ), asList(
                        outboxFrame(0, 1),
                        wm(0),
                        outboxFrame(1, 2),
                        wm(1),
                        outboxFrame(2, 3),
                        wm(2),
                        outboxFrame(3, 4),
                        wm(3),
                        outboxFrame(4, 4),
                        wm(4),
                        outboxFrame(5, 3),
                        wm(5),
                        outboxFrame(6, 2),
                        wm(6),
                        outboxFrame(7, 1),
                        wm(7)
                ), true, true, false, false, Objects::equals);
    }

    @Test
    public void when_receiveRandomTimestamps_then_emitAscending() {
        // Given
        final List<Long> timestampsToAdd = LongStream.range(0, 100).boxed().collect(toList());
        shuffle(timestampsToAdd);
        ArrayList<Object> inbox = new ArrayList<>();
        for (long ts : timestampsToAdd) {
            inbox.add(event(ts, 1));
        }
        for (long i = 0; i <= 105; i++) {
            inbox.add(wm(i));
        }

        List<Object> expectedOutbox = new ArrayList<>();
        expectedOutbox.addAll(Arrays.asList(
                outboxFrame(0, 1),
                wm(0),
                outboxFrame(1, 2),
                wm(1),
                outboxFrame(2, 3),
                wm(2),
                outboxFrame(3, 4),
                wm(3)
        ));
        for (long ts = 4; ts < 100; ts++) {
            expectedOutbox.add(outboxFrame(ts, 4));
            expectedOutbox.add(wm(ts));
        }
        expectedOutbox.addAll(Arrays.asList(
                outboxFrame(100, 3),
                wm(100),
                outboxFrame(101, 2),
                wm(101),
                outboxFrame(102, 1),
                wm(102),
                wm(103),
                wm(104),
                wm(105)
        ));
        testProcessor(supplier, inbox, expectedOutbox, true, true, false, false, Objects::equals);
    }

    @Test
    public void when_wmNeverReceived_then_emitEverythingInComplete() {
        long start = System.nanoTime();
        testProcessor(supplier,
                asList(
                        event(0L, 1L), // to frame 0
                        event(1L, 1L) // to frame 1
                        // no WM to emit any window, everything should be emitted in complete as if we received
                        // wm(3), wm(4)
                ),
                asList(
                        outboxFrame(3, 2),
                        outboxFrame(4, 1)
                ), true, true, false, true, Objects::equals);

        long processTime = System.nanoTime() - start;
        // this is to test that there is no iteration from current watermark up to Long.MAX_VALUE, which
        // will take too long.
        assertTrue("process took too long: " + processTime, processTime < MILLISECONDS.toNanos(300));
    }


    @Test
    public void when_missedWm_then_error() {
        exception.expectMessage("probably missed a WM");

        testProcessor(supplier,
                asList(
                        event(0L, 1L), // to frame 0
                        // We should have received wm(3), which includes frames 0..3, where 0 is our bottom frame.
                        // Receiving wm(4) should produce and error, because it will leave leak frame0
                        wm(4L)
                ),
                emptyList(), true, true, false, false, Objects::equals);
    }

    private Entry<Long, ?> event(long frameTs, long value) {
        return singleStageProcessor
                // frameTs is higher than any event timestamp in that frame;
                // therefore we generate an event with frameTs - 1
                ? entry(frameTs - 1, value)
                : new TimestampedEntry<>(frameTs, KEY, new LongAccumulator(value));
    }

    private static TimestampedEntry<Long, ?> outboxFrame(long ts, long value) {
        return new TimestampedEntry<>(ts, KEY, value);
    }

    private static Watermark wm(long timestamp) {
        return new Watermark(timestamp);
    }
}
