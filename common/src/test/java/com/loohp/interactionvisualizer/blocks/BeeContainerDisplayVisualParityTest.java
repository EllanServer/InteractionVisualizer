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

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeeContainerDisplayVisualParityTest {

    @Test
    void hiveAndNestLabelsUseOnlyTheLegacyNameTagGeometry() throws ReflectiveOperationException {
        DisplayEntity hiveLabel = EntityHolderTestFactory.allocate(DisplayEntity.class);
        DisplayEntity nestLabel = EntityHolderTestFactory.allocate(DisplayEntity.class);
        initializeTextDefaults(hiveLabel);
        initializeTextDefaults(nestLabel);

        allocateWithoutConstructor(BeeHiveDisplay.class).setStand(hiveLabel);
        allocateWithoutConstructor(BeeNestDisplay.class).setStand(nestLabel);

        assertLegacyNameTagGeometry(hiveLabel);
        assertLegacyNameTagGeometry(nestLabel);
    }

    @Test
    void hiveAndNestLabelsKeepTheExactUpstreamLogicalAnchors() {
        Location block = new Location(null, 10.0, 64.0, -4.0);

        for (BlockFace facing : new BlockFace[] {
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST}) {
            assertUpstreamAnchors(block, facing, BeeHiveDisplay::labelLocation);
            assertUpstreamAnchors(block, facing, BeeNestDisplay::labelLocation);
        }

        assertEquals(10.0, block.getX());
        assertEquals(64.0, block.getY());
        assertEquals(-4.0, block.getZ(), "the block anchor must not be mutated");
    }

    private static void assertLegacyNameTagGeometry(DisplayEntity label) {
        assertTrue(label.usesLegacyNameTagGeometry());
        assertFalse(label.usesLegacyNameTagStyle());
        assertEquals(1.0F, label.getTextScale());
        assertEquals(Display.Billboard.FIXED, label.getBillboard());
        assertFalse(label.isDefaultBackground());
    }

    private static void assertUpstreamAnchors(Location block, BlockFace facing,
                                               LabelLocationFactory factory) {
        double expectedX = switch (facing) {
            case WEST -> 9.8;
            case EAST -> 11.2;
            default -> 10.5;
        };
        double expectedZ = switch (facing) {
            case NORTH -> -4.2;
            case SOUTH -> -2.8;
            default -> -3.5;
        };

        Location honey = factory.create(block, facing, 0.25);
        Location bees = factory.create(block, facing, 0.0);

        assertEquals(expectedX, honey.getX(), 1.0E-12);
        assertEquals(64.25, honey.getY(), 1.0E-12);
        assertEquals(expectedZ, honey.getZ(), 1.0E-12);
        assertEquals(expectedX, bees.getX(), 1.0E-12);
        assertEquals(64.0, bees.getY(), 1.0E-12);
        assertEquals(expectedZ, bees.getZ(), 1.0E-12);
    }

    @FunctionalInterface
    private interface LabelLocationFactory {
        Location create(Location blockLocation, BlockFace facing, double lineOffset);
    }

    private static <T> T allocateWithoutConstructor(Class<T> type) throws ReflectiveOperationException {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
        return type.cast(allocateInstance.invoke(unsafeField.get(null), type));
    }

    private static void initializeTextDefaults(DisplayEntity label) throws ReflectiveOperationException {
        Field billboard = DisplayEntity.class.getDeclaredField("billboard");
        billboard.setAccessible(true);
        billboard.set(label, Display.Billboard.FIXED);
        Field textScale = DisplayEntity.class.getDeclaredField("textScale");
        textScale.setAccessible(true);
        textScale.setFloat(label, 0.5F);
    }
}
