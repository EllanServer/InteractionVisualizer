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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceMetricsShutdownSnapshotTest {

    @Test
    void zeroSnapshotIsCleanAndMachineReadable() {
        PerformanceMetrics.ShutdownSnapshot snapshot = new PerformanceMetrics.ShutdownSnapshot(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        assertTrue(snapshot.clean());
        assertEquals(0, snapshot.totalRetained());
        assertTrue(snapshot.json().contains("\"trackingWorlds\":0"));
        assertTrue(snapshot.json().contains("\"totalRetained\":0"));
    }

    @Test
    void everyRetainedCategoryContributesToTotal() {
        PerformanceMetrics.ShutdownSnapshot snapshot = new PerformanceMetrics.ShutdownSnapshot(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18);

        assertFalse(snapshot.clean());
        assertEquals(171, snapshot.totalRetained());
        assertTrue(snapshot.summary().contains("total=171"));
        assertTrue(snapshot.json().contains("\"cullingRegistrations\":16"));
    }
}
