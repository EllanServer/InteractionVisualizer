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
        assertFalse(viewers.usesGrid(world));
    }

    @Test
    void smallViewerSetsStayLinearWithExactWorldAndVerticalDistance() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex();
        for (int index = 0; index < 50; index++) {
            viewers.addViewer(world, index, 64.0D, 0.0D);
        }
        viewers.addViewer(UUID.randomUUID(), 0.0D, 64.0D, 0.0D);

        assertTrue(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D, 0.0D));
        assertFalse(viewers.hasViewerWithin(world, 0.0D, 65.0001D, 0.0D, 1.0D));
        assertFalse(viewers.usesGrid(world));
    }

    @Test
    void largeViewerSetsLazilyBuildTheGridWithoutChangingExactMatches() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex();
        for (int index = 0; index < DroppedItemSpatialIndex.ViewerIndex.DEFAULT_MINIMUM_GRID_VIEWERS; index++) {
            viewers.addViewer(world, index * 4.0D, 64.0D + index % 3, index * -3.0D);
        }

        assertTrue(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D, 0.0D));
        assertFalse(viewers.usesGrid(world));
        int extra = DroppedItemSpatialIndex.ViewerIndex.DEFAULT_MINIMUM_GRID_VIEWERS;
        viewers.addViewer(world, extra * 4.0D, 64.0D + extra % 3, extra * -3.0D);
        assertTrue(viewers.hasViewerWithin(world, 0.0D, 64.0D, 0.0D, 0.0D));
        assertTrue(viewers.usesGrid(world));
        assertFalse(viewers.hasViewerWithin(world, 0.0D, 66.0001D, 0.0D, 2.0D));
        assertFalse(viewers.hasViewerWithin(UUID.randomUUID(), 0.0D, 64.0D, 0.0D, 1.0D));
    }

    @Test
    void gridKeepsNegativeCoordinatesAndLaterViewersExact() {
        UUID world = UUID.randomUUID();
        DroppedItemSpatialIndex.ViewerIndex viewers = new DroppedItemSpatialIndex.ViewerIndex(0);
        for (int index = 0; index < 8; index++) {
            viewers.addViewer(world, 1_000.0D + index * 16.0D, 64.0D, 1_000.0D);
        }
        viewers.addViewer(world, -16.1D, 64.0D, -31.9D);

        assertTrue(viewers.hasViewerWithin(world, -16.0D, 64.0D, -32.0D, 0.15D));
        assertTrue(viewers.usesGrid(world));
        assertFalse(viewers.hasViewerWithin(world, -15.9D, 64.0D, -32.0D, 0.15D));

        viewers.addViewer(world, -0.01D, 70.0D, -0.01D);
        assertTrue(viewers.hasViewerWithin(world, 0.0D, 70.0D, 0.0D, 0.02D));
        assertFalse(viewers.hasViewerWithin(world, 0.0D, 70.03D, 0.0D, 0.02D));
    }
}
