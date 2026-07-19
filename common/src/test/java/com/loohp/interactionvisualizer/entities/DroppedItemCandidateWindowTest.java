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
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DroppedItemCandidateWindowTest {

    @Test
    void retainedBoundaryIncludesEveryPossibleCrampNeighbourAndNoRequiredOutsider() {
        UUID world = UUID.randomUUID();
        double viewDistance = 64.0D;
        double retainedBoundary = viewDistance + DroppedItemDisplay.VIEW_DISTANCE_HYSTERESIS;
        double queryDistance = DroppedItemDisplay.sourceQueryDistance(viewDistance, true);
        Point candidate = new Point(retainedBoundary, 0.0D, 0.0D);
        List<Point> points = List.of(
                candidate,
                new Point(retainedBoundary + 0.5D, 0.0D, 0.0D),
                new Point(retainedBoundary - 0.5D, 0.5D, -0.5D),
                new Point(retainedBoundary + 0.500_001D, 0.0D, 0.0D),
                new Point(retainedBoundary + 4.0D, 0.0D, 0.0D));

        DroppedItemSpatialIndex full = new DroppedItemSpatialIndex();
        DroppedItemSpatialIndex local = new DroppedItemSpatialIndex();
        for (Point point : points) {
            full.addItem(world, point.x(), point.y(), point.z());
            if (Math.abs(point.x()) <= queryDistance
                    && Math.abs(point.y()) <= queryDistance
                    && Math.abs(point.z()) <= queryDistance) {
                local.addItem(world, point.x(), point.y(), point.z());
            }
        }

        assertEquals(DroppedItemDisplay.RETAINED_CANDIDATE,
                DroppedItemDisplay.classifyCandidate(candidate.x(), candidate.y(), candidate.z(),
                        viewDistance, true));
        for (int maximum = 1; maximum <= 4; maximum++) {
            assertEquals(
                    full.exceedsItemLimit(world, candidate.x(), candidate.y(), candidate.z(), maximum),
                    local.exceedsItemLimit(world, candidate.x(), candidate.y(), candidate.z(), maximum));
        }
    }

    @Test
    void sourceOwnedSectionQueryPreservesBruteForceCandidateAndCrampResults() {
        Random random = new Random(0x495643524f50534cL);
        UUID world = UUID.randomUUID();
        double viewDistance = 64.0D;
        double queryDistance = DroppedItemDisplay.sourceQueryDistance(viewDistance, true);

        for (int run = 0; run < 100; run++) {
            List<Point> points = new ArrayList<>();
            for (int index = 0; index < 512; index++) {
                points.add(new Point(
                        random.nextDouble(-96.0D, 96.0D),
                        random.nextDouble(-96.0D, 96.0D),
                        random.nextDouble(-96.0D, 96.0D)));
            }

            DroppedItemSpatialIndex full = new DroppedItemSpatialIndex();
            DroppedItemSpatialIndex local = new DroppedItemSpatialIndex();
            for (Point point : points) {
                full.addItem(world, point.x(), point.y(), point.z());
                if (Math.abs(point.x()) <= queryDistance
                        && Math.abs(point.y()) <= queryDistance
                        && Math.abs(point.z()) <= queryDistance) {
                    local.addItem(world, point.x(), point.y(), point.z());
                }
            }

            for (Point point : points) {
                boolean hasLabel = random.nextBoolean();
                int expected = bruteClassification(point, viewDistance, hasLabel);
                int actual = DroppedItemDisplay.classifyCandidate(
                        point.x(), point.y(), point.z(), viewDistance, hasLabel);
                assertEquals(expected, actual);
                if (actual != DroppedItemDisplay.OUTSIDE_CANDIDATE_RANGE) {
                    for (int maximum = 1; maximum <= 6; maximum++) {
                        assertEquals(
                                full.exceedsItemLimit(world, point.x(), point.y(), point.z(), maximum),
                                local.exceedsItemLimit(world, point.x(), point.y(), point.z(), maximum));
                    }
                }
            }
        }
    }

    private static int bruteClassification(Point point, double viewDistance, boolean hasLabel) {
        double distanceSquared = point.x() * point.x()
                + point.y() * point.y() + point.z() * point.z();
        if (distanceSquared <= viewDistance * viewDistance) {
            return DroppedItemDisplay.DESIRED_CANDIDATE;
        }
        double retained = viewDistance + DroppedItemDisplay.VIEW_DISTANCE_HYSTERESIS;
        return hasLabel && distanceSquared <= retained * retained
                ? DroppedItemDisplay.RETAINED_CANDIDATE
                : DroppedItemDisplay.OUTSIDE_CANDIDATE_RANGE;
    }

    private record Point(double x, double y, double z) {
    }
}
