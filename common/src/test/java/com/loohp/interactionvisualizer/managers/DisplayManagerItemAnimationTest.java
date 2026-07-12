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

package com.loohp.interactionvisualizer.managers;

import com.loohp.interactionvisualizer.entityholders.Item;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayManagerItemAnimationTest {

    @Test
    void stationaryItemsDoNotEnterTheAnimationLoop() {
        assertFalse(DisplayManager.requiresItemAnimation(false, new Vector()));
    }

    @Test
    void gravityAndVelocityActivateTheAnimationLoop() {
        assertTrue(DisplayManager.requiresItemAnimation(true, new Vector()));
        assertTrue(DisplayManager.requiresItemAnimation(false, new Vector(0.0, 0.05, 0.0)));
    }

    @Test
    void staticAnchorExperimentNeverDetachesVisibleNames() {
        assertFalse(DisplayManager.useStaticAnchorForAnimation(false, false));
        assertFalse(DisplayManager.useStaticAnchorForAnimation(false, true));
        assertTrue(DisplayManager.useStaticAnchorForAnimation(true, false));
        assertFalse(DisplayManager.useStaticAnchorForAnimation(true, true));
    }

    @Test
    void copiesTheNmsAirMovementOrderAndPrecision() {
        Vector movement = DisplayManager.itemMovementForTick(true, new Vector(0.18, 0.15, 0.05));
        assertEquals(new Vector(0.18, 0.11, 0.05), movement);

        Vector nextVelocity = DisplayManager.itemVelocityAfterMovement(movement);
        assertEquals(movement.getX() * (double) 0.98F, nextVelocity.getX());
        assertEquals(movement.getY() * 0.98D, nextVelocity.getY());
        assertEquals(movement.getZ() * (double) 0.98F, nextVelocity.getZ());
    }

    @Test
    @SuppressWarnings("unchecked")
    void claimingPickupIdsRemovesOnlyLocalBookkeeping() throws ReflectiveOperationException {
        Field idsField = DisplayManager.class.getDeclaredField("virtualItemIds");
        idsField.setAccessible(true);
        Map<Item, Map<UUID, Integer>> allIds = (Map<Item, Map<UUID, Integer>>) idsField.get(null);
        Method forget = DisplayManager.class.getDeclaredMethod("forgetVirtualItem", Item.class, UUID.class);
        forget.setAccessible(true);

        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Item logical = (Item) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafeField.get(null), Item.class);
        UUID firstViewer = UUID.randomUUID();
        UUID secondViewer = UUID.randomUUID();
        Map<UUID, Integer> viewerIds = new ConcurrentHashMap<>();
        viewerIds.put(firstViewer, 41);
        viewerIds.put(secondViewer, 42);
        allIds.put(logical, viewerIds);

        try {
            assertEquals(41, forget.invoke(null, logical, firstViewer));
            assertEquals(Map.of(secondViewer, 42), allIds.get(logical));
            assertEquals(42, forget.invoke(null, logical, secondViewer));
            assertFalse(allIds.containsKey(logical));
            assertNull(forget.invoke(null, logical, secondViewer));
        } finally {
            allIds.remove(logical);
        }
    }
}
