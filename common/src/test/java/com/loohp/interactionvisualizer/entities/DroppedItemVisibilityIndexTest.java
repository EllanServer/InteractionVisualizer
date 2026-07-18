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

package com.loohp.interactionvisualizer.entities;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DroppedItemVisibilityIndexTest {

    @Test
    void matchesBruteForceAcrossWorldsAndCellBoundaries() {
        UUID firstWorld = UUID.randomUUID();
        UUID secondWorld = UUID.randomUUID();
        List<TestPoint> points = new ArrayList<>();
        DroppedItemVisibilityIndex<Integer> index = new DroppedItemVisibilityIndex<>();
        Random random = new Random(0x1A2B3C4DL);
        for (int id = 0; id < 4096; id++) {
            UUID world = (id & 3) == 0 ? secondWorld : firstWorld;
            double x = random.nextDouble(-2048.0D, 2048.0D);
            double y = random.nextDouble(-64.0D, 384.0D);
            double z = random.nextDouble(-2048.0D, 2048.0D);
            points.add(new TestPoint(world, x, y, z, id));
            index.add(world, x, y, z, id);
        }

        for (int query = 0; query < 250; query++) {
            UUID world = (query & 1) == 0 ? firstWorld : secondWorld;
            double x = random.nextDouble(-2048.0D, 2048.0D);
            double y = random.nextDouble(-64.0D, 384.0D);
            double z = random.nextDouble(-2048.0D, 2048.0D);
            double range = random.nextDouble(0.0D, 96.0D);
            Set<Integer> expected = bruteForce(points, world, x, y, z, range);
            Set<Integer> actual = new HashSet<>();

            index.queryInto(world, x, y, z, range, actual);

            assertEquals(expected, actual);
        }
    }

    @Test
    void includesTheExactSphereEdgeAndRejectsOutsideY() {
        UUID world = UUID.randomUUID();
        DroppedItemVisibilityIndex<String> index = new DroppedItemVisibilityIndex<>();
        index.add(world, 16.0D, 74.0D, -16.0D, "edge");
        index.add(world, 16.0D, 74.0001D, -16.0D, "outside");
        Set<String> matches = new HashSet<>();

        int candidates = index.queryInto(world, 16.0D, 64.0D, -16.0D, 10.0D, matches);

        assertEquals(Set.of("edge"), matches);
        assertEquals(2, candidates);
    }

    @Test
    void sparseSceneInspectsFarLessThanTheGlobalItemCount() {
        UUID world = UUID.randomUUID();
        DroppedItemVisibilityIndex<Integer> index = new DroppedItemVisibilityIndex<>();
        for (int id = 0; id < 2048; id++) {
            index.add(world, id * 32.0D, 64.0D, id * 32.0D, id);
        }
        Set<Integer> matches = new HashSet<>();

        int candidates = index.queryInto(world, 0.0D, 64.0D, 0.0D, 64.0D, matches);

        assertTrue(candidates < 16);
        assertEquals(Set.of(0, 1), matches);
    }

    private static Set<Integer> bruteForce(List<TestPoint> points, UUID world,
                                           double x, double y, double z, double range) {
        Set<Integer> matches = new HashSet<>();
        double rangeSquared = range * range;
        for (TestPoint point : points) {
            if (!point.world.equals(world)) {
                continue;
            }
            double deltaX = point.x - x;
            double deltaY = point.y - y;
            double deltaZ = point.z - z;
            if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= rangeSquared) {
                matches.add(point.id);
            }
        }
        return matches;
    }

    private record TestPoint(UUID world, double x, double y, double z, int id) {
    }
}
