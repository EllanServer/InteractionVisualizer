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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DroppedItemSpatialIndexTest {

    @Test
    void preservesTheOriginalHalfBlockCrampingBoxAcrossCellBoundaries() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex index = new DroppedItemSpatialIndex();
        index.addItem(world, 0.49D, 64.0D, 0.49D);
        index.addItem(world, 0.99D, 64.5D, -0.01D);

        assertTrue(index.exceedsItemLimit(world, 0.49D, 64.0D, 0.49D, 1));
        assertFalse(index.exceedsItemLimit(world, 0.49D, 64.0D, 0.49D, 2));
    }

    @Test
    void excludesItemsOutsideTheBoxAndItemsInOtherWorlds() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex index = new DroppedItemSpatialIndex();
        index.addItem(world, 0.0D, 64.0D, 0.0D);
        index.addItem(world, 0.5001D, 64.0D, 0.0D);
        index.addItem(UUID.randomUUID(), 0.0D, 64.0D, 0.0D);

        assertFalse(index.exceedsItemLimit(world, 0.0D, 64.0D, 0.0D, 1));
    }

    @Test
    void findsViewersAcrossChunkBoundariesUsingExactDistance() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex();
        viewers.addViewer(world, 16.1D, 64.0D, 0.0D);

        assertTrue(viewers.hasViewerWithin(world, 15.9D, 64.0D, 0.0D, 0.3D));
        assertFalse(viewers.hasViewerWithin(world, 15.9D, 64.0D, 0.0D, 0.1D));
        assertFalse(viewers.hasViewerWithin(UUID.randomUUID(), 15.9D, 64.0D, 0.0D, 1.0D));
    }

    @Test
    void primitiveViewerStorageExpandsWithoutLosingExactMatches() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex();
        for (int index = 0; index < 257; index++) {
            viewers.addViewer(world, -1024.25D + index * 8.5D, 40.0D + index % 7, -index * 3.25D);
        }

        assertTrue(viewers.hasViewerWithin(world, -1024.25D, 40.0D, 0.0D, 0.0D));
        assertTrue(viewers.hasViewerWithin(world,
                -1024.25D + 256 * 8.5D, 40.0D + 256 % 7, -256 * 3.25D, 0.0D));
        assertFalse(viewers.hasViewerWithin(world, 5000.0D, 5000.0D, 5000.0D, 1.0D));
    }

    @Test
    void rejectsNegativeExpectedViewerCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> new DroppedItemSpatialIndex.ViewerIndex(-1));
    }

    @Test
    void viewerBoundsKeepTouchingSphereEdgesExact() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex(2);
        viewers.addViewer(world, -10.0D, 64.0D, -10.0D);
        viewers.addViewer(world, 10.0D, 70.0D, 10.0D);

        assertTrue(viewers.hasViewerWithin(world, 20.0D, 70.0D, 10.0D, 10.0D));
        assertFalse(viewers.hasViewerWithin(world, 20.0001D, 70.0D, 10.0D, 10.0D));
        assertTrue(viewers.hasViewerWithin(world, -10.0D, 54.0D, -10.0D, 10.0D));
        assertFalse(viewers.hasViewerWithin(world, -10.0D, 53.9999D, -10.0D, 10.0D));
    }

    @Test
    void viewerBoundsStillRunExactChecksInsideTheBox() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex(4);
        viewers.addViewer(world, -100.0D, 64.0D, -100.0D);
        viewers.addViewer(world, -100.0D, 64.0D, 100.0D);
        viewers.addViewer(world, 100.0D, 64.0D, -100.0D);
        viewers.addViewer(world, 100.0D, 64.0D, 100.0D);

        assertFalse(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D, 10.0D));
        assertTrue(viewers.hasViewerWithin(world, 90.0D, 64.0D, 100.0D, 10.0D));
    }

    @Test
    void internalMissesBuildAndLaterHitsRetireTheAdaptiveGrid() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex(768);
        addViewerRing(viewers, world, 768, 300.0D);

        for (int query = 0; query < 8; query++) {
            assertFalse(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D,
                    72.0D, 299 - query));
        }
        assertTrue(viewers.hasAdaptiveGrid(world));
        assertTrue(viewers.isUsingAdaptiveGrid(world));
        assertTrue(viewers.hasViewerWithin(world, 300.0D, 64.0D, 0.0D, 0.0D));
        assertFalse(viewers.isUsingAdaptiveGrid(world));
        assertTrue(viewers.hasAdaptiveGrid(world));

        viewers.addViewer(world, 0.0D, 64.0D, 0.0D);
        assertFalse(viewers.hasAdaptiveGrid(world));
        assertTrue(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D, 0.0D));
    }

    @Test
    void adaptiveGridIsNotBuiltWithoutEnoughRemainingQueries() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex(768);
        addViewerRing(viewers, world, 768, 300.0D);

        for (int query = 0; query < 8; query++) {
            assertFalse(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D,
                    72.0D, 7 - query));
        }
        assertFalse(viewers.hasAdaptiveGrid(world));
    }

    @Test
    void adaptiveGridRechecksChangingQueryRanges() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex(768);
        addViewerRing(viewers, world, 768, 300.0D);
        for (int query = 0; query < 8; query++) {
            assertFalse(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D,
                    72.0D, 299 - query));
        }

        assertTrue(viewers.isUsingAdaptiveGrid(world));
        assertTrue(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D, Double.MAX_VALUE));
        assertFalse(viewers.isUsingAdaptiveGrid(world));
        assertTrue(viewers.hasAdaptiveGrid(world));
    }

    @Test
    void lateViewerHitsBuildAndKeepTheAdaptiveGrid() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex(192);
        addViewerRing(viewers, world, 191, 300.0D);
        viewers.addViewer(world, 0.0D, 64.0D, 0.0D);

        for (int query = 0; query < 8; query++) {
            assertTrue(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D,
                    72.0D, 299 - query));
        }
        assertTrue(viewers.hasAdaptiveGrid(world));
        assertTrue(viewers.isUsingAdaptiveGrid(world));
        for (int query = 0; query < 64; query++) {
            assertTrue(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D,
                    72.0D, 291 - query));
        }
        assertTrue(viewers.isUsingAdaptiveGrid(world));
    }

    @Test
    void viewerBeforeDeepScanThresholdStaysOnThePrimitiveFastPath() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex(191);
        addViewerRing(viewers, world, 190, 300.0D);
        viewers.addViewer(world, 0.0D, 64.0D, 0.0D);

        for (int query = 0; query < 16; query++) {
            assertTrue(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D,
                    72.0D, 511 - query));
        }
        assertFalse(viewers.hasAdaptiveGrid(world));
        assertFalse(viewers.isUsingAdaptiveGrid(world));
        assertFalse(viewers.hasActiveBounds(world));
    }

    @Test
    void addingViewerResetsPartiallyPrimedAdaptiveState() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex(193);
        addViewerRing(viewers, world, 191, 300.0D);
        viewers.addViewer(world, 0.0D, 64.0D, 0.0D);

        for (int query = 0; query < 7; query++) {
            assertTrue(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D,
                    72.0D, 511 - query));
        }
        assertFalse(viewers.hasAdaptiveGrid(world));

        viewers.addViewer(world, 600.0D, 64.0D, 0.0D);
        assertTrue(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D, 72.0D, 504));
        assertFalse(viewers.hasAdaptiveGrid(world));
        for (int query = 0; query < 7; query++) {
            assertTrue(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D,
                    72.0D, 503 - query));
        }
        assertTrue(viewers.hasAdaptiveGrid(world));
        assertTrue(viewers.isUsingAdaptiveGrid(world));
    }

    @Test
    void earlyHitClearsPartiallyPrimedDeepScanState() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex(192);
        addViewerRing(viewers, world, 191, 300.0D);
        viewers.addViewer(world, 0.0D, 64.0D, 0.0D);

        for (int query = 0; query < 7; query++) {
            assertTrue(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D,
                    72.0D, 511 - query));
        }
        assertTrue(viewers.hasViewerWithin(world, 300.0D, 64.0D, 0.0D, 0.0D, 504));
        for (int query = 0; query < 7; query++) {
            assertTrue(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D,
                    72.0D, 503 - query));
        }
        assertFalse(viewers.hasAdaptiveGrid(world));
        assertTrue(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D, 72.0D, 496));
        assertTrue(viewers.hasAdaptiveGrid(world));
    }

    @Test
    void outsideMissDoesNotAdvanceInternalMissCounter() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex(768);
        addViewerRing(viewers, world, 768, 300.0D);

        for (int query = 0; query < 7; query++) {
            assertFalse(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D,
                    72.0D, 511 - query));
        }
        assertFalse(viewers.hasViewerWithin(world, 1000.0D, 64.0D, 0.0D, 72.0D, 504));
        assertFalse(viewers.hasAdaptiveGrid(world));
        assertTrue(viewers.hasActiveBounds(world));
        assertFalse(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D, 72.0D, 503));
        assertTrue(viewers.hasAdaptiveGrid(world));
    }

    @Test
    void denseDiagonalMissesStayOnThePrimitiveScan() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex(384);
        for (int index = 0; index < 384; index++) {
            double x = (index & 1) == 0 ? -62.0D : 62.0D;
            double z = (index & 2) == 0 ? -62.0D : 62.0D;
            viewers.addViewer(world, x, 64.0D, z);
        }
        for (int query = 0; query < 72; query++) {
            assertFalse(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D,
                    72.0D, 499 - query));
        }
        assertFalse(viewers.hasAdaptiveGrid(world));
        assertTrue(viewers.hasViewerWithin(world, -62.0D, 64.0D, -62.0D, 0.0D, 427));
        assertFalse(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D, 72.0D, 426));
        assertFalse(viewers.hasAdaptiveGrid(world));
    }

    @Test
    void adaptiveGridUsesPerWorldQueryBudgets() {
        UUID shortWorld = UUID.randomUUID();
        UUID longWorld = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex(1536);
        addViewerRing(viewers, shortWorld, 768, 300.0D);
        addViewerRing(viewers, longWorld, 768, 300.0D);

        for (int query = 0; query < 8; query++) {
            assertFalse(viewers.hasViewerWithin(shortWorld, 0.0D, 64.0D, 0.0D,
                    72.0D, 7 - query));
            assertFalse(viewers.hasViewerWithin(longWorld, 0.0D, 64.0D, 0.0D,
                    72.0D, 299 - query));
        }
        assertFalse(viewers.hasAdaptiveGrid(shortWorld));
        assertTrue(viewers.hasAdaptiveGrid(longWorld));
    }

    @Test
    void upgradesFromSingleWorldFastPathAndKeepsWorldsIsolated() {
        UUID firstWorld = UUID.randomUUID();
        UUID secondWorld = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex();
        viewers.addViewer(firstWorld, -0.01D, 70.0D, -16.1D);

        assertTrue(viewers.hasViewerWithin(firstWorld, 0.0D, 70.0D, -16.0D, 0.15D));
        assertFalse(viewers.hasViewerWithin(secondWorld, 0.0D, 70.0D, -16.0D, 0.15D));

        viewers.addViewer(secondWorld, 0.0D, 90.0D, 0.0D);
        viewers.addViewer(firstWorld, 32.0D, 72.0D, 32.0D);
        assertTrue(viewers.hasViewerWithin(secondWorld, 0.0D, 90.0D, 0.0D, 0.0D));
        assertFalse(viewers.hasViewerWithin(firstWorld, 0.0D, 90.0D, 0.0D, 0.0D));
        assertTrue(viewers.hasViewerWithin(firstWorld, 32.0D, 72.0D, 32.0D, 0.0D));
        assertFalse(viewers.hasViewerWithin(firstWorld, 32.0D, 73.0001D, 32.0D, 1.0D));
    }

    private static void addViewerRing(DroppedItemSpatialIndex.ViewerIndex viewers, UUID world,
                                      int count, double radius) {
        for (int index = 0; index < count; index++) {
            double angle = 2.0D * Math.PI * index / count;
            viewers.addViewer(world, Math.cos(angle) * radius, 64.0D,
                    Math.sin(angle) * radius);
        }
    }
}
