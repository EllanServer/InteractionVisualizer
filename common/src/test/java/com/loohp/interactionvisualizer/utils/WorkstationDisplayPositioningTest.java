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

package com.loohp.interactionvisualizer.utils;

import com.loohp.interactionvisualizer.entityholders.EntityHolderTestFactory;
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.entityholders.VisualizerEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkstationDisplayPositioningTest {

    @Test
    void gridAnchorClearsTheBlockTopForDirectItemDisplays() {
        Location origin = new Location(null, 10.0, 64.0, -5.0);

        Location anchor = WorkstationDisplayPositioning.gridAnchor(origin);

        assertNotSame(origin, anchor);
        assertEquals(10.5, anchor.getX());
        assertEquals(65.0, anchor.getY());
        assertEquals(-4.5, anchor.getZ());
        assertEquals(64.0, origin.getY());
    }

    @Test
    void gridSlotsStayCentredAcrossCardinalDirections() {
        Location origin = new Location(null, 10.0, 64.0, -5.0);

        assertGridSlot(origin, 0.0F, -0.2, 0.2, 10.7, 65.0, -4.3);
        assertGridSlot(origin, 90.0F, -0.2, 0.2, 10.3, 65.0, -4.3);
        assertGridSlot(origin, 180.0F, -0.2, 0.2, 10.3, 65.0, -4.7);
        assertGridSlot(origin, -90.0F, -0.2, 0.2, 10.7, 65.0, -4.7);

        assertEquals(10.0, origin.getX());
        assertEquals(64.0, origin.getY());
        assertEquals(-5.0, origin.getZ());
    }

    @Test
    void crafterNorthGridStartsOnTheBlockTop() {
        Location block = new Location(null, 125.0, -52.0, -23.0);

        Location slotOne = WorkstationDisplayPositioning.gridSlot(block, 180.0F, -0.2, 0.2);

        assertEquals(125.3, slotOne.getX(), 1.0E-9);
        assertEquals(-51.0, slotOne.getY(), 1.0E-9);
        assertEquals(-22.7, slotOne.getZ(), 1.0E-9);
        assertEquals(180.0F, slotOne.getYaw());
    }

    @Test
    void renderModeChangesPreserveTheGridAnchor() throws ReflectiveOperationException {
        Item item = testWorkstationItem(Item.RenderMode.ITEM,
                new Location(null, 1.0, 2.0, 3.0, 10.0F, 0.0F), 10.0F);

        WorkstationDisplayPositioning.setRenderMode(item, Item.RenderMode.BLOCK);
        assertEquals(Item.RenderMode.BLOCK, item.getRenderMode());
        assertEquals(55.0F, item.getLocation().getYaw());
        assertEquals(1.0, item.getLocation().getX());
        assertEquals(2.0, item.getLocation().getY());
        assertEquals(3.0, item.getLocation().getZ());

        WorkstationDisplayPositioning.setRenderMode(item, Item.RenderMode.LOW_BLOCK);
        assertEquals(Item.RenderMode.LOW_BLOCK, item.getRenderMode());
        assertEquals(55.0F, item.getLocation().getYaw());
        assertEquals(1.0, item.getLocation().getX());
        assertEquals(2.0, item.getLocation().getY());
        assertEquals(3.0, item.getLocation().getZ());

        WorkstationDisplayPositioning.setRenderMode(item, Item.RenderMode.ITEM);
        assertEquals(Item.RenderMode.ITEM, item.getRenderMode());
        assertEquals(10.0F, item.getLocation().getYaw());
        assertEquals(1.0, item.getLocation().getX());
        assertEquals(2.0, item.getLocation().getY());
        assertEquals(3.0, item.getLocation().getZ());

        WorkstationDisplayPositioning.setRenderMode(item, Item.RenderMode.TOOL);
        assertEquals(Item.RenderMode.TOOL, item.getRenderMode());
        assertEquals(10.0F, item.getLocation().getYaw());
        assertEquals(1.0, item.getLocation().getX());
        assertEquals(2.0, item.getLocation().getY());
        assertEquals(3.0, item.getLocation().getZ());
    }

    @Test
    void workstationModeRejectsPacketAndSpecializedDisplays() throws ReflectiveOperationException {
        Item item = testItem(Item.RenderMode.ITEM, new Location(null, 0.0, 0.0, 0.0));

        assertThrows(IllegalArgumentException.class,
                () -> WorkstationDisplayPositioning.setRenderMode(item, Item.RenderMode.DROPPED));
        assertThrows(IllegalArgumentException.class,
                () -> WorkstationDisplayPositioning.setRenderMode(item, Item.RenderMode.BANNER));
        assertThrows(IllegalArgumentException.class,
                () -> WorkstationDisplayPositioning.setRenderMode(item, Item.RenderMode.FRAME));
    }

    @Test
    void workstationMarkerSelectsTheRightHandContextWithoutChangingOtherFixedItems()
            throws ReflectiveOperationException {
        Item workstation = testWorkstationItem(Item.RenderMode.ITEM,
                new Location(null, 1.0, 2.0, 3.0, 10.0F, 0.0F), 10.0F);
        Item ordinary = testItem(Item.RenderMode.ITEM,
                new Location(null, 1.0, 2.0, 3.0, 10.0F, 0.0F));

        assertEquals(ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND,
                DisplayTransformFactory.itemDisplayTransform(workstation));
        assertEquals(ItemDisplay.ItemDisplayTransform.FIXED,
                DisplayTransformFactory.itemDisplayTransform(ordinary));
        assertEquals(ItemDisplay.ItemDisplayTransform.HEAD,
                DisplayTransformFactory.itemDisplayTransform(Item.RenderMode.BANNER));
        assertEquals(ItemDisplay.ItemDisplayTransform.FIXED,
                DisplayTransformFactory.itemDisplayTransform(Item.RenderMode.FRAME));
    }

    @Test
    void workstationModesMatchTheLegacySmallArmorStandGoldenMatrices()
            throws ReflectiveOperationException {
        assertGoldenMatrix(Item.RenderMode.ITEM, 10.0F,
                new float[] {
                        -0.000386488F, -0.023218991F, -0.080493137F,
                        0.499613523F, -0.023218904F, -0.080493182F,
                        -0.000386444F, -0.023218991F, 0.419506848F,
                        -0.000386400F, -0.523218989F, -0.080493137F
                });
        assertGoldenMatrix(Item.RenderMode.BLOCK, 55.0F,
                new float[] {
                        0.014817633F, 0.084806390F, -0.071107179F,
                        0.514817655F, 0.084806472F, -0.071107246F,
                        0.014817676F, 0.204419374F, 0.414374799F,
                        0.014817731F, -0.400675595F, 0.048505813F
                });
        assertGoldenMatrix(Item.RenderMode.LOW_BLOCK, 55.0F,
                new float[] {
                        0.013403438F, 0.020806497F, -0.055550832F,
                        0.513403416F, 0.020806583F, -0.055550896F,
                        0.013403481F, 0.140419483F, 0.429931134F,
                        0.013403536F, -0.464675486F, 0.064062163F
                });
        assertGoldenMatrix(Item.RenderMode.TOOL, 10.0F,
                new float[] {
                        0.030960985F, 0.004655909F, -0.134024560F,
                        0.019913029F, 0.504533827F, -0.134024575F,
                        -0.044516824F, 0.002987763F, 0.360242873F,
                        0.525107741F, 0.015577200F, -0.058528319F
                });
        assertGoldenMatrix(Item.RenderMode.STANDING, 10.0F,
                new float[] {
                        0.019560307F, 0.036030862F, 0.002479957F,
                        0.019636948F, 0.029835742F, -0.497481644F,
                        0.013365188F, 0.535954118F, -0.003715637F,
                        0.519521952F, 0.042226456F, 0.002479826F
                });
    }

    @Test
    void sideFacingSlotsKeepTheCommonAnchorAlignedToTheWorkstationGrid()
            throws ReflectiveOperationException {
        Item gridFacing = testWorkstationItem(Item.RenderMode.ITEM,
                new Location(null, 0.0, 0.0, 0.0, 10.0F, 0.0F), 10.0F);
        Item sideFacing = testWorkstationItem(Item.RenderMode.ITEM,
                new Location(null, 0.0, 0.0, 0.0, 30.0F, 0.0F), 10.0F);

        Vector3f gridOrigin = DisplayTransformFactory.item(gridFacing).transformPosition(new Vector3f());
        Vector3f sideOrigin = DisplayTransformFactory.item(sideFacing).transformPosition(new Vector3f());

        assertEquals(-0.06019086F, sideOrigin.x - gridOrigin.x, 2.0E-6F);
        assertEquals(0.0F, sideOrigin.y - gridOrigin.y, 2.0E-6F);
        assertEquals(-0.05537303F, sideOrigin.z - gridOrigin.z, 2.0E-6F);
    }

    private static Item testItem(Item.RenderMode mode, Location location) throws ReflectiveOperationException {
        Item item = EntityHolderTestFactory.allocate(Item.class);
        initializeItem(item, mode, location);
        return item;
    }

    private static Item testWorkstationItem(Item.RenderMode mode, Location location, float gridYaw)
            throws ReflectiveOperationException {
        WorkstationDisplayPositioning.WorkstationItem item =
                EntityHolderTestFactory.allocate(WorkstationDisplayPositioning.WorkstationItem.class);
        Field workstationYaw = WorkstationDisplayPositioning.WorkstationItem.class.getDeclaredField("gridYaw");
        workstationYaw.setAccessible(true);
        workstationYaw.setFloat(item, gridYaw);
        initializeItem(item, mode, location);
        return item;
    }

    private static void initializeItem(Item item, Item.RenderMode mode, Location location)
            throws ReflectiveOperationException {
        Field renderMode = Item.class.getDeclaredField("renderMode");
        renderMode.setAccessible(true);
        renderMode.set(item, mode);
        Field entityLocation = VisualizerEntity.class.getDeclaredField("location");
        entityLocation.setAccessible(true);
        entityLocation.set(item, location);
    }

    private static void assertGoldenMatrix(Item.RenderMode mode, float displayYaw, float[] expected)
            throws ReflectiveOperationException {
        Item item = testWorkstationItem(mode,
                new Location(null, 0.0, 0.0, 0.0, displayYaw, 0.0F), 10.0F);
        Matrix4f matrix = DisplayTransformFactory.item(item);
        Vector3f[] probes = {
                new Vector3f(),
                new Vector3f(1.0F, 0.0F, 0.0F),
                new Vector3f(0.0F, 1.0F, 0.0F),
                new Vector3f(0.0F, 0.0F, 1.0F)
        };
        for (int index = 0; index < probes.length; index++) {
            Vector3f actual = matrix.transformPosition(probes[index]);
            int offset = index * 3;
            assertEquals(expected[offset], actual.x, 2.0E-6F, mode + " probe " + index + " x");
            assertEquals(expected[offset + 1], actual.y, 2.0E-6F, mode + " probe " + index + " y");
            assertEquals(expected[offset + 2], actual.z, 2.0E-6F, mode + " probe " + index + " z");
        }
    }

    private static void assertGridSlot(Location origin, float yaw, double lateral, double forward,
                                       double expectedX, double expectedY, double expectedZ) {
        Location slot = WorkstationDisplayPositioning.gridSlot(origin, yaw, lateral, forward);
        assertEquals(expectedX, slot.getX(), 1.0E-9);
        assertEquals(expectedY, slot.getY(), 1.0E-9);
        assertEquals(expectedZ, slot.getZ(), 1.0E-9);
        assertEquals(yaw, slot.getYaw());
    }
}
