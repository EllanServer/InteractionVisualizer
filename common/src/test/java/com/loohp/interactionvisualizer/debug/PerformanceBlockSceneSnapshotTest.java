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

package com.loohp.interactionvisualizer.debug;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerformanceBlockSceneSnapshotTest {

    @Test
    void summaryPublishesLastMutationTickWindowAndElapsedNanos() {
        PerformanceBlockScene.Snapshot snapshot = snapshot(
                410L, 411L, 12_345_678L, 8_000_000L, 3_000_000L);

        assertEquals(410L, snapshot.lastMutationStartBukkitTick());
        assertEquals(411L, snapshot.lastMutationEndBukkitTick());
        assertEquals(12_345_678L, snapshot.lastMutationElapsedNanos());
        assertEquals(8_000_000L, snapshot.lastMutationWriteElapsedNanos());
        assertEquals(3_000_000L, snapshot.lastMutationInspectionElapsedNanos());

        Map<String, String> fields = summaryFields(snapshot);
        assertEquals("410", fields.get("mutationStartBukkitTick"));
        assertEquals("411", fields.get("mutationEndBukkitTick"));
        assertEquals("12.345678", fields.get("mutationElapsedMs"));
        assertEquals("8.000000", fields.get("mutationWriteMs"));
        assertEquals("3.000000", fields.get("mutationInspectionMs"));
    }

    @Test
    void summaryKeepsNoMutationSentinelsObservable() {
        Map<String, String> fields = summaryFields(snapshot(-1L, -1L, 0L, 0L, 0L));

        assertEquals("-1", fields.get("mutationStartBukkitTick"));
        assertEquals("-1", fields.get("mutationEndBukkitTick"));
        assertEquals("0.000000", fields.get("mutationElapsedMs"));
        assertEquals("0.000000", fields.get("mutationWriteMs"));
        assertEquals("0.000000", fields.get("mutationInspectionMs"));
    }

    private static PerformanceBlockScene.Snapshot snapshot(long startTick, long endTick, long elapsedNanos,
                                                           long writeElapsedNanos,
                                                           long inspectionElapsedNanos) {
        return new PerformanceBlockScene.Snapshot(
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                PerformanceBlockScene.SceneState.READY,
                PerformanceBlockScene.Mode.DIRECT_WRITE,
                5, 5, 5, 0, 5, 0,
                1, 1, 1, 1, 1,
                7L, 5, 5,
                startTick, endTick, elapsedNanos, writeElapsedNanos, inspectionElapsedNanos,
                0, 0, 0, 0,
                "eventless_direct_write");
    }

    private static Map<String, String> summaryFields(PerformanceBlockScene.Snapshot snapshot) {
        return Stream.of(snapshot.summary().split(" "))
                .map(field -> field.split("=", 2))
                .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
    }
}
