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

import com.hazelcast.jet.core.Processor.Context;
import com.hazelcast.jet.core.Watermark;
import com.hazelcast.jet.core.WatermarkEmissionPolicy;
import com.hazelcast.jet.core.WatermarkPolicy;
import com.hazelcast.jet.core.test.TestOutbox;
import com.hazelcast.jet.core.test.TestProcessorContext;
import com.hazelcast.jet.function.DistributedSupplier;
import com.hazelcast.test.HazelcastParametersRunnerFactory;
import com.hazelcast.test.annotation.ParallelTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static com.hazelcast.jet.core.WatermarkEmissionPolicy.emitByFrame;
import static com.hazelcast.jet.core.WatermarkEmissionPolicy.emitByMinStep;
import static com.hazelcast.jet.core.WatermarkGenerationParams.wmGenParams;
import static com.hazelcast.jet.core.WatermarkPolicies.withFixedLag;
import static com.hazelcast.jet.core.WindowDefinition.tumblingWindowDef;
import static com.hazelcast.jet.impl.execution.WatermarkCoalescer.IDLE_MESSAGE;
import static com.hazelcast.jet.impl.util.WatermarkPolicyUtil.limitingTimestampAndWallClockLag;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
@Category(ParallelTest.class)
@Parameterized.UseParametersRunnerFactory(HazelcastParametersRunnerFactory.class)
public class InsertWatermarksPTest {

    private static final long LAG = 3;

    @Parameter
    public int outboxCapacity;

    private MockClock clock = new MockClock(100);
    private InsertWatermarksP<Item> p;
    private TestOutbox outbox;
    private List<Object> resultToCheck = new ArrayList<>();
    private Context context;
    private DistributedSupplier<WatermarkPolicy> wmPolicy = withFixedLag(LAG);
    private WatermarkEmissionPolicy wmEmissionPolicy = (WatermarkEmissionPolicy) (currentWm, lastEmittedWm) ->
            currentWm > lastEmittedWm;

    @Parameters(name = "outboxCapacity={0}")
    public static Collection<Object> parameters() {
        return asList(1, 1024);
    }

    @Before
    public void setUp() {
        outbox = new TestOutbox(outboxCapacity);
        context = new TestProcessorContext();
    }

    @Test
    public void when_firstEventLate_then_notDropped() {
        wmPolicy = limitingTimestampAndWallClockLag(0, 0, clock::now);
        doTest(
                singletonList(item(clock.now - 1)),
                asList(wm(clock.now), item(clock.now - 1)));
    }

    @Test
    public void when_manyEvents_then_oneWm() {
        doTest(
                asList(
                        item(10),
                        item(10)),
                asList(
                        wm(7),
                        item(10),
                        item(10))
        );
    }

    @Test
    public void when_eventsIncrease_then_wmIncreases() {
        doTest(
                asList(
                        item(10),
                        item(11)),
                asList(
                        wm(7),
                        item(10),
                        wm(8),
                        item(11))
        );
    }

    @Test
    public void when_eventsDecrease_then_oneWm() {
        doTest(
                asList(
                        item(11),
                        item(10)),
                asList(
                        wm(8),
                        item(11),
                        item(10))
        );
    }

    @Test
    public void when_lateEvent_then_notDropped() {
        doTest(
                asList(
                        item(11),
                        item(7)),
                asList(
                        wm(8),
                        item(11),
                        item(7))
        );
    }

    @Test
    public void when_gapBetweenEvents_then_oneWm() {
        doTest(
                asList(
                        item(10),
                        item(13)),
                asList(
                        wm(7),
                        item(10),
                        wm(10),
                        item(13))
        );
    }

    @Test
    public void when_zeroLag() {
        wmPolicy = withFixedLag(0);
        doTest(
                asList(
                        item(10),
                        item(13)),
                asList(
                        wm(10),
                        item(10),
                        wm(13),
                        item(13))
        );
    }

    @Test
    public void emitByFrame_when_eventsIncrease_then_wmIncreases() {
        wmEmissionPolicy = emitByFrame(tumblingWindowDef(2));
        doTest(
                asList(
                        item(10),
                        item(11),
                        item(12),
                        item(13)
                ),
                asList(
                        wm(7), // corresponds to frame(6)
                        item(10),
                        wm(8), // corresponds to frame(8)
                        item(11),
                        item(12),
                        wm(10), // corresponds to frame(10)
                        item(13)
                )
        );
    }

    @Test
    public void emitByFrame_when_eventsIncreaseAndStartAtVergeOfFrame_then_wmIncreases() {
        wmEmissionPolicy = emitByFrame(tumblingWindowDef(2));
        doTest(
                asList(
                        item(11),
                        item(12),
                        item(13),
                        item(14)
                ),
                asList(
                        wm(8),
                        item(11),
                        item(12),
                        wm(10),
                        item(13),
                        item(14)
                )
        );

    }

    @Test
    public void emitByFrame_when_gapBetweenEvents_then_gapInWms() {
        wmEmissionPolicy = emitByFrame(tumblingWindowDef(2));
        doTest(
                asList(
                        item(11),
                        item(15)),
                asList(
                        wm(8),
                        item(11),
                        wm(12),
                        item(15))
        );
    }

    @Test
    public void emitByMinStep_when_eventsIncrease_then_wmIncreases() {
        wmEmissionPolicy = emitByMinStep(2);
        doTest(
                asList(
                        item(11),
                        item(12),
                        item(13),
                        item(14)
                ),
                asList(
                        wm(8),
                        item(11),
                        item(12),
                        wm(10),
                        item(13),
                        item(14)
                )
        );
    }

    @Test
    public void emitByMinStep_when_gapBetweenEvents_then_oneWm() {
        wmEmissionPolicy = emitByMinStep(2);
        doTest(
                asList(
                        item(10),
                        item(15),
                        item(20)),
                asList(
                        wm(7),
                        item(10),
                        wm(12),
                        item(15),
                        wm(17),
                        item(20))
        );
    }

    @Test
    public void when_idleTimeout_then_idleMessageAfterTimeout() {
        // We can't inject MockClock to WatermarkSourceUtil inside the InsertWatermarkP, so we use real time.
        // We send no events and expect, that after 100 ms WM will be emitted.
        createProcessor(100);

        // let's process some event and expect real WM to be emitted
        resultToCheck.clear();
        doAndDrain(() -> p.tryProcess(0, item(10)));
        assertEquals(asList(wm(10 - LAG), item(10)), resultToCheck);

        // when no more activity occurs, IDLE_MESSAGE should be emitted again
        resultToCheck.clear();

        long start = System.nanoTime();
        long elapsedMs;
        do {
            assertTrue(p.tryProcess());
            elapsedMs = NANOSECONDS.toMillis(System.nanoTime() - start);
            drainOutbox();
            if (elapsedMs < 99) {
                assertTrue("outbox should be empty, elapsedMs=" + elapsedMs, resultToCheck.isEmpty());
            } else if (!resultToCheck.isEmpty()) {
                System.out.println("WM emitted after " + elapsedMs + "ms (shortly after 100 was expected)");
                assertEquals(singletonList(IDLE_MESSAGE), resultToCheck);
                break;
            }
            LockSupport.parkNanos(MILLISECONDS.toNanos(1));
        } while (elapsedMs < 1000);
    }

    private void createProcessor(long idleTimeoutMillis) {
        p = new InsertWatermarksP<>(wmGenParams(Item::getTimestamp, wmPolicy, wmEmissionPolicy, idleTimeoutMillis));
        p.init(outbox, context);
    }

    private void doTest(List<Object> input, List<Object> expectedOutput) {
        if (p == null) {
            createProcessor(-1);
        }

        for (Object inputItem : input) {
            if (inputItem instanceof Tick) {
                clock.set(((Tick) inputItem).timestamp);
                resultToCheck.add(tick(clock.now));
                doAndDrain(p::tryProcess);
            } else {
                assertTrue(inputItem instanceof Item);
                doAndDrain(() -> p.tryProcess(0, inputItem));
            }
        }

        assertEquals(listToString(expectedOutput), listToString(resultToCheck));
    }

    private void doAndDrain(BooleanSupplier action) {
        boolean done;
        int count = 0;
        do {
            done = action.getAsBoolean();
            drainOutbox();
            assertTrue("action not done in " + count + " attempts", ++count < 10);
        } while (!done);
    }

    private void drainOutbox() {
        resultToCheck.addAll(outbox.queueWithOrdinal(0));
        outbox.queueWithOrdinal(0).clear();
    }

    private String myToString(Object o) {
        return o instanceof Watermark
                ? "Watermark{timestamp=" + ((Watermark) o).timestamp() + "}"
                : o.toString();
    }

    private String listToString(List<?> actual) {
        return actual.stream().map(this::myToString).collect(Collectors.joining("\n"));
    }

    private static Item item(long timestamp) {
        return new Item(timestamp);
    }

    private static Watermark wm(long timestamp) {
        return new Watermark(timestamp);
    }

    private static Tick tick(long timestamp) {
        return new Tick(timestamp);
    }

    private static final class Tick {
        final long timestamp;

        private Tick(long timestamp) {
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            return "-- at " + timestamp;
        }
    }

    private static class Item {
        final long timestamp;

        Item(long timestamp) {
            this.timestamp = timestamp;
        }

        long getTimestamp() {
            return timestamp;
        }

        @Override
        public String toString() {
            return "Item{timestamp=" + timestamp + '}';
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof Item && this.timestamp == ((Item) o).timestamp;
        }

        @Override
        public int hashCode() {
            return (int) (timestamp ^ (timestamp >>> 32));
        }
    }

    private static class MockClock {
        long now;

        MockClock(long now) {
            this.now = now;
        }

        long now() {
            return now;
        }

        void set(long newNow) {
            assert newNow >= now;
            now = newNow;
        }

    }
}
