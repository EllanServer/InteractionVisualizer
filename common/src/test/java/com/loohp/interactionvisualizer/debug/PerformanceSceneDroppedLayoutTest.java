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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceSceneDroppedLayoutTest {

    @Test
    void separatesGlobalTrackedPopulationFromNearbyLabels() {
        List<PerformanceScene.DroppedItemPlacement> placements =
                PerformanceScene.droppedItemPlacements(2_048, 128);

        assertEquals(2_048, placements.size());
        assertEquals(128, placements.stream().filter(
                PerformanceScene.DroppedItemPlacement::nearby).count());
        assertTrue(placements.stream().filter(
                        PerformanceScene.DroppedItemPlacement::nearby)
                .allMatch(placement -> distance(placement) < 16.0D));
        assertTrue(placements.stream().filter(
                        placement -> !placement.nearby())
                .allMatch(placement -> distance(placement) > 128.0D));
    }

    @Test
    void largestSupportedFarGridStaysOutsideCandidateQueryMargin() {
        List<PerformanceScene.DroppedItemPlacement> placements =
                PerformanceScene.droppedItemPlacements(8_192, 1);

        assertEquals(8_192, placements.size());
        assertTrue(placements.stream().filter(
                        placement -> !placement.nearby())
                .allMatch(placement -> distance(placement) > 128.0D));
    }

    @Test
    void legacyAllNearbyLayoutRemainsAvailable() {
        List<PerformanceScene.DroppedItemPlacement> placements =
                PerformanceScene.droppedItemPlacements(2_048, 2_048);

        assertEquals(2_048, placements.size());
        assertTrue(placements.stream().allMatch(
                PerformanceScene.DroppedItemPlacement::nearby));
    }

    private static double distance(PerformanceScene.DroppedItemPlacement placement) {
        return Math.hypot(placement.xOffset(), placement.zOffset());
    }
}
