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

package com.hazelcast.jet.impl.execution;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static org.junit.Assert.assertEquals;

public class BarrierWatcherTest {

    @Rule
    public ExpectedException exception = ExpectedException.none();

    private BarrierWatcher bw = new BarrierWatcher(2);

    @Test
    public void test_barrierIncrementsOnAllQueues() {
        doAndCheck(0, 1, false, true, false);
        doAndCheck(1, 1, true, false, false);
        doAndCheck(0, 2, false, true, false);
        doAndCheck(1, 2, true, false, false);
    }

    @Test
    public void test_barrierIncrementsTwiceOnOneQueue() {
        doAndCheck(0, 1, false, true, false);
        doAndCheck(0, 2, false, true, false);
        doAndCheck(1, 1, true, true, false);
        doAndCheck(1, 2, true, false, false);
    }

    @Test
    public void when_barrierDoesNotIncrement_then_error() {
        bw.observe(0, 1);
        exception.expect(AssertionError.class);
        bw.observe(0, 1);
    }

    @Test
    public void when_startAtZero_then_error() {
        exception.expect(AssertionError.class);
        bw.observe(0, 0);
    }

    @Test
    public void test_skippedBarrier() {
        doAndCheck(0, 2, false, true, false);
        doAndCheck(1, 1, true, true, false);
        doAndCheck(1, 2, true, false, false);
    }

    @Test
    public void test_markQueueDone() {
        doAndCheck(0, 1, false, true, false);
        doAndCheck(1, 1, true, false, false);
        assertEquals(1, bw.markQueueDone(0));
        doAndCheck(1, 2, true, false, false);
    }

    @Test
    public void when_allQueuesDone_then_maxValue() {
        assertEquals(0, bw.markQueueDone(0));
        assertEquals(Long.MAX_VALUE, bw.markQueueDone(1));
    }

    @Test
    public void test_markQueueDoneWithoutAnyBarrier() {
        doAndCheck(1, 1, false, false, true);
        assertEquals(1, bw.markQueueDone(0));
        doAndCheck(1, 2, true, false, false);
    }

    private void doAndCheck(int queueIndex, long snapshotId, boolean canForward,
                            boolean q0Blocked, boolean q1Blocked) {
        assertEquals("canForward does not match", canForward, bw.observe(queueIndex, snapshotId));
        assertEquals("q0Blocked does not match", q0Blocked, bw.isBlocked(0));
        assertEquals("q1Blocked does not match", q1Blocked, bw.isBlocked(1));
    }

}