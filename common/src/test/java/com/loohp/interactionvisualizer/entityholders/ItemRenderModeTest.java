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

package com.loohp.interactionvisualizer.entityholders;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemRenderModeTest {

    @Test
    void renderModesSeparateDroppedItemsFromFixedDisplays() {
        assertFalse(Item.RenderMode.DROPPED.isFixedDisplay());
        assertTrue(Item.RenderMode.ITEM.isFixedDisplay());
        assertTrue(Item.RenderMode.BLOCK.isFixedDisplay());
        assertTrue(Item.RenderMode.LOW_BLOCK.isFixedDisplay());
        assertTrue(Item.RenderMode.TOOL.isFixedDisplay());
        assertTrue(Item.RenderMode.STANDING.isFixedDisplay());
        assertTrue(Item.RenderMode.BANNER.isFixedDisplay());
        assertTrue(Item.RenderMode.FRAME.isFixedDisplay());
    }

    @Test
    void frameRotationUsesVanillaItemFrameBounds() throws ReflectiveOperationException {
        Item frame = newUninitializedItem();
        Field rotation = Item.class.getDeclaredField("frameRotation");
        rotation.setAccessible(true);
        rotation.setInt(frame, 7);

        assertEquals(7, frame.getFrameRotation());
        assertThrows(IllegalArgumentException.class, () -> frame.setFrameRotation(8));
    }

    private static Item newUninitializedItem() throws ReflectiveOperationException {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return (Item) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafeField.get(null), Item.class);
    }
}
