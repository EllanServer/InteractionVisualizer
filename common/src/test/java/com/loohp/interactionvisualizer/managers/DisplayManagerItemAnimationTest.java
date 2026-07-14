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
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
    void virtualMotionPacketsAreSkippedForUnchangedStaticItems() {
        assertFalse(DisplayManager.requiresVirtualItemMotionSync(false, false, false));
        assertTrue(DisplayManager.requiresVirtualItemMotionSync(true, false, false));
        assertTrue(DisplayManager.requiresVirtualItemMotionSync(false, true, false));
        assertTrue(DisplayManager.requiresVirtualItemMotionSync(false, false, true));
    }

    @Test
    void staticAnchorExperimentNeverDetachesVisibleNames() {
        assertFalse(DisplayManager.useStaticAnchorForAnimation(false, false));
        assertFalse(DisplayManager.useStaticAnchorForAnimation(false, true));
        assertTrue(DisplayManager.useStaticAnchorForAnimation(true, false));
        assertFalse(DisplayManager.useStaticAnchorForAnimation(true, true));
    }

    @Test
    void packetOnlyStaticItemsRequireEverySafeProperty() {
        assertTrue(DisplayManager.qualifiesForPacketOnlyStatic(
                true, false, new Vector(), false, false));

        assertFalse(DisplayManager.qualifiesForPacketOnlyStatic(
                false, false, new Vector(), false, false));
        assertFalse(DisplayManager.qualifiesForPacketOnlyStatic(
                true, true, new Vector(), false, false));
        assertFalse(DisplayManager.qualifiesForPacketOnlyStatic(
                true, false, new Vector(0.0, 0.01, 0.0), false, false));
        assertFalse(DisplayManager.qualifiesForPacketOnlyStatic(
                true, false, new Vector(Double.MIN_VALUE, 0.0, 0.0), false, false));
        assertFalse(DisplayManager.qualifiesForPacketOnlyStatic(
                true, false, new Vector(), true, false));
        assertFalse(DisplayManager.qualifiesForPacketOnlyStatic(
                true, false, new Vector(), false, true));
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
    void sameWorldLogicalTeleportResetsAnExistingAnimationPath() {
        Location originalLogical = new Location(null, 1.0, 64.0, 1.0);
        Location previousAnimation = new Location(null, 4.0, 65.0, 4.0);
        Location teleportedLogical = new Location(null, 20.0, 70.0, 20.0);
        Location actualAtTeleport = teleportedLogical.clone();

        assertFalse(DisplayManager.shouldApplyItemLogicalLocation(
                true, previousAnimation, originalLogical, originalLogical.clone()));
        assertEquals(previousAnimation, DisplayManager.itemAnimationStartPosition(
                actualAtTeleport, previousAnimation, originalLogical, originalLogical.clone()));

        assertTrue(DisplayManager.shouldApplyItemLogicalLocation(
                true, previousAnimation, originalLogical, teleportedLogical));
        assertEquals(actualAtTeleport, DisplayManager.itemAnimationStartPosition(
                actualAtTeleport, previousAnimation, originalLogical, teleportedLogical));
    }

    @Test
    void endingAnAnimationRestoresTheLogicalLocation() {
        Location logical = new Location(null, 1.0, 64.0, 1.0);
        Location previousAnimation = new Location(null, 4.0, 65.0, 4.0);

        assertTrue(DisplayManager.shouldApplyItemLogicalLocation(
                false, previousAnimation, logical, logical.clone()));
    }

    @Test
    void animatedChunkIndexFollowsTheMovingEntityUnlessTheAnchorIsStatic() {
        Location anchor = new Location(null, 15.9, 64.0, 0.0);
        Location acrossChunkBoundary = new Location(null, 16.1, 64.0, 0.0);

        assertEquals(acrossChunkBoundary, DisplayManager.itemAnimationIndexLocation(
                false, anchor, acrossChunkBoundary));
        assertEquals(anchor, DisplayManager.itemAnimationIndexLocation(
                true, anchor, acrossChunkBoundary));
    }

    @Test
    void chunkIndexReportsOnlyExistingEntryCrossings()
            throws ReflectiveOperationException {
        UUID worldId = UUID.randomUUID();
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUID" -> worldId;
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "animation-index-test-world";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Item logical = newUninitializedItem();

        try {
            assertFalse(DisplayManager.index(logical, new Location(world, 1.0, 64.0, 1.0)));
            assertFalse(DisplayManager.index(logical, new Location(world, 15.9, 64.0, 1.0)));
            assertTrue(DisplayManager.index(logical, new Location(world, 16.1, 64.0, 1.0)));
        } finally {
            DisplayManager.unindex(logical);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void claimingPickupIdsRemovesOnlyLocalBookkeeping() throws ReflectiveOperationException {
        Field idsField = DisplayManager.class.getDeclaredField("virtualItemIds");
        idsField.setAccessible(true);
        Map<Item, Map<UUID, Integer>> allIds = (Map<Item, Map<UUID, Integer>>) idsField.get(null);
        Method forget = DisplayManager.class.getDeclaredMethod("forgetVirtualItem", Item.class, UUID.class);
        forget.setAccessible(true);

        Item logical = newUninitializedItem();
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

    @Test
    void rateLimitedOneShotPickupMaterializesOnlyEligibleMissingViewer() {
        assertTrue(DisplayManager.shouldMaterializeRateLimitedPickupViewer(true, true, true, false));

        assertFalse(DisplayManager.shouldMaterializeRateLimitedPickupViewer(false, true, true, false));
        assertFalse(DisplayManager.shouldMaterializeRateLimitedPickupViewer(true, false, true, false));
        assertFalse(DisplayManager.shouldMaterializeRateLimitedPickupViewer(true, true, false, false));
        assertFalse(DisplayManager.shouldMaterializeRateLimitedPickupViewer(true, true, true, true));
    }

    private static Item newUninitializedItem() throws ReflectiveOperationException {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return (Item) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafeField.get(null), Item.class);
    }
}
