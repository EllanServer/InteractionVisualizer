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
    }
}
