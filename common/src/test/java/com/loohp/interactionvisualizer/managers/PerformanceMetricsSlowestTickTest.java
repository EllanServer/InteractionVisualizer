/*
 * This file is part of InteractionVisualizer.
 *
 * Copyright (C) 2026. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.loohp.interactionvisualizer.managers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceMetricsSlowestTickTest {

    @Test
    void attributesAggregatedBlockWorkToStrictlySlowestCompletedTick() {
        PerformanceMetrics.SlowestTickTracker tracker = new PerformanceMetrics.SlowestTickTracker();

        tracker.blockUpdateChecks(2, 3_000_000L);
        tracker.blockUpdateChecks(3, 5_000_000L);
        tracker.completeTick(100, 10.0D, 1_000L);
        assertSlowest(tracker, 100, 5, 8_000_000L);
        assertEquals(1_000L, tracker.slowestEndEpochMillis());

        tracker.blockUpdateChecks(7, 9_000_000L);
        tracker.completeTick(101, 5.0D, 2_000L);
        assertSlowest(tracker, 100, 5, 8_000_000L);
        assertEquals(1_000L, tracker.slowestEndEpochMillis());

        tracker.blockUpdateChecks(11, 13_000_000L);
        tracker.completeTick(102, 20.0D, 3_000L);
        assertSlowest(tracker, 102, 11, 13_000_000L);
        assertEquals(3_000L, tracker.slowestEndEpochMillis());

        tracker.blockUpdateChecks(17, 19_000_000L);
        tracker.completeTick(103, 20.0D, 4_000L);
        assertSlowest(tracker, 102, 11, 13_000_000L);
        assertEquals(3_000L, tracker.slowestEndEpochMillis());
    }

    @Test
    void discardedAndCompletedTicksDoNotLeakCurrentWork() {
        PerformanceMetrics.SlowestTickTracker tracker = new PerformanceMetrics.SlowestTickTracker();

        tracker.blockUpdateChecks(23, 29_000_000L);
        tracker.discardTick();
        tracker.completeTick(200, 1.0D);

        assertSlowest(tracker, 200, 0, 0L);

        tracker.blockUpdateChecks(31, 37_000_000L);
        tracker.completeTick(201, 2.0D);
        tracker.completeTick(202, 3.0D);

        assertSlowest(tracker, 202, 0, 0L);
    }

    @Test
    void resetRestoresNoSampleSentinel() {
        PerformanceMetrics.SlowestTickTracker tracker = new PerformanceMetrics.SlowestTickTracker();
        tracker.blockUpdateChecks(1, 2L);
        tracker.completeTick(300, 3.0D);

        tracker.reset();

        assertSlowest(tracker, -1, 0, 0L);
        assertEquals(-1L, tracker.slowestEndEpochMillis());
    }

    @Test
    void snapshotJsonPublishesSlowestTickAttributionInMilliseconds() {
        PerformanceMetrics.Snapshot snapshot = new PerformanceMetrics.Snapshot(
                "diagnostic", false, false, false, false, 128, 32,
                new PerformanceMetrics.DroppedLabelVisibilityConfig(true, 64, true, 128, 32),
                true, true, 64, true,
                1_000_000_000L, 20, 0L,
                1.0D, 2.0D, 3.0D, 4.0D, 50.25D,
                4242, 1_783_951_200_123L, 7L, 12_345_678L,
                5.0D, 1L,
                0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0,
                0L, 0L, 0L, 0L, 0L, 2048, 2048, 7L, 12_345_678L, 0, 0, 0,
                0L, 0L, 0, 0L, 0L, 100L, 5L, 200L);

        String json = snapshot.json();

        assertTrue(json.contains("\"msptMax\":50.250000"));
        assertTrue(json.contains("\"msptMaxBukkitTick\":4242"));
        assertTrue(json.contains("\"msptMaxEndEpochMillis\":1783951200123"));
        assertTrue(json.contains("\"msptMaxBlockUpdateChecks\":7"));
        assertTrue(json.contains("\"msptMaxBlockUpdateMs\":12.345678"));
        assertTrue(json.contains("\"droppedLabelVisibilityCulling\":true"));
        assertTrue(json.contains("\"droppedLabelViewDistance\":64"));
        assertTrue(json.contains("\"droppedSourceOwnedSectionCandidates\":true"));
        assertTrue(json.contains("\"droppedLabelVisibilityRateLimit\":true"));
        assertTrue(json.contains("\"droppedLabelVisibilityBucketSize\":128"));
        assertTrue(json.contains("\"droppedLabelVisibilityRestorePerTick\":32"));
        assertTrue(json.contains("\"viewerFullReconciles\":0"));
        assertTrue(json.contains("\"craftEngineCullingRetainedRegistrations\":0"));
        assertTrue(json.contains("\"droppedSpatialCandidates\":0"));
        assertTrue(json.contains("\"droppedTrackedItemsMax\":2048"));
        assertTrue(json.contains("\"droppedLabelsMax\":2048"));
        assertTrue(json.contains("\"blockUpdateDirtyQueueMax\":0"));
        assertTrue(json.contains("\"preferenceSqlStatements\":0"));
        assertTrue(json.contains("\"legacyTextCacheHits\":95"));
        assertTrue(json.contains("\"legacyTextCacheHitRate\":0.950000"));
        assertTrue(json.contains("\"legacyTextSameRawFastPaths\":200"));
    }

    private static void assertSlowest(PerformanceMetrics.SlowestTickTracker tracker,
                                      int tick, long checks, long nanos) {
        assertEquals(tick, tracker.slowestBukkitTick());
        assertEquals(checks, tracker.slowestBlockUpdateChecks());
        assertEquals(nanos, tracker.slowestBlockUpdateNanos());
    }
}
