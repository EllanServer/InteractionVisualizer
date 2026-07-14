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

package com.loohp.interactionvisualizer.blocks;

import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.EntityHolderTestFactory;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LecternDisplayOrientationTest {

    @Test
    void bookLabelsFaceTheViewerLikeLegacyNameTags() {
        assertEquals(Display.Billboard.CENTER, LecternDisplay.labelBillboard());
    }

    @Test
    void labelAnchorsMatchLegacyOffsetsForEveryLecternFacing() {
        Location origin = new Location(null, 10.0D, 64.0D, 20.0D);

        assertLocation(LecternDisplay.firstLineLocation(origin, BlockFace.NORTH),
                10.5D, 65.301D, 20.3D);
        assertLocation(LecternDisplay.firstLineLocation(origin, BlockFace.SOUTH),
                10.5D, 65.301D, 20.7D);
        assertLocation(LecternDisplay.firstLineLocation(origin, BlockFace.WEST),
                10.3D, 65.301D, 20.5D);
        assertLocation(LecternDisplay.firstLineLocation(origin, BlockFace.EAST),
                10.7D, 65.301D, 20.5D);
        assertLocation(LecternDisplay.secondLineLocation(origin, BlockFace.NORTH),
                10.5D, 65.001D, 20.3D);
    }

    @Test
    void labelsUseTheSharedLegacyNameTagProfile() throws ReflectiveOperationException {
        DisplayEntity label = EntityHolderTestFactory.allocate(DisplayEntity.class);

        LecternDisplay.setStand(label);

        assertTrue(label.usesLegacyNameTagStyle());
        assertEquals(1.0F, label.getTextScale());
        assertTrue(label.isDefaultBackground());
        assertEquals(Display.Billboard.CENTER, label.getBillboard());
    }

    private static void assertLocation(Location actual, double x, double y, double z) {
        assertEquals(x, actual.getX(), 1.0E-12D);
        assertEquals(y, actual.getY(), 1.0E-12D);
        assertEquals(z, actual.getZ(), 1.0E-12D);
    }
}
