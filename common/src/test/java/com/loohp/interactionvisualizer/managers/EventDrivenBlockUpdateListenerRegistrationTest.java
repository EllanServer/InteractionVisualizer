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

import com.loohp.interactionvisualizer.api.events.TileEntityRemovedEvent;
import com.loohp.interactionvisualizer.blocks.BeeHiveDisplay;
import com.loohp.interactionvisualizer.blocks.FurnaceDisplay;
import com.loohp.interactionvisualizer.blocks.SmokerDisplay;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class EventDrivenBlockUpdateListenerRegistrationTest {

    @Test
    void eventDrivenOnlyHandlersAreAbsentFromAlwaysRegisteredListeners() throws Exception {
        assertNotRegistered(FurnaceDisplay.class, "onFurnaceBurn", FurnaceBurnEvent.class);
        assertNotRegistered(BeeHiveDisplay.class, "onAffectedBlockPlace", BlockPlaceEvent.class);
        assertNotRegistered(SmokerDisplay.class, "onRemoveSmoker", TileEntityRemovedEvent.class);
        assertNotRegistered(TileEntityManager.class, "onChunkLoad", ChunkLoadEvent.class);
    }

    @Test
    void legacyHandlersRemainOnAlwaysRegisteredListeners() throws Exception {
        assertRegistered(FurnaceDisplay.class, "onUseFurnace", InventoryClickEvent.class);
        assertRegistered(TileEntityManager.class, "onPlayerMove", PlayerMoveEvent.class);
    }

    @Test
    void conditionalListenerOwnsTheEventDrivenSurface() throws Exception {
        assertRegistered(EventDrivenBlockUpdateListener.class, "onFurnaceBurn", FurnaceBurnEvent.class);
        assertRegistered(EventDrivenBlockUpdateListener.class, "onAffectedBlockPlace", BlockPlaceEvent.class);
        assertRegistered(EventDrivenBlockUpdateListener.class, "onTileEntityRemoved", TileEntityRemovedEvent.class);
        Class<?> lifecycleListener = findDeclaredClass(TileEntityManager.class, "EventDrivenLifecycleListener");
        assertRegistered(lifecycleListener, "onChunkLoad", ChunkLoadEvent.class);
    }

    @Test
    void hotPathMaterialRoutingIsExclusive() {
        assertEquals(EventDrivenBlockUpdateListener.FurnaceTarget.FURNACE,
                EventDrivenBlockUpdateListener.furnaceTarget(Material.FURNACE));
        assertEquals(EventDrivenBlockUpdateListener.FurnaceTarget.BLAST_FURNACE,
                EventDrivenBlockUpdateListener.furnaceTarget(Material.BLAST_FURNACE));
        assertEquals(EventDrivenBlockUpdateListener.FurnaceTarget.SMOKER,
                EventDrivenBlockUpdateListener.furnaceTarget(Material.SMOKER));
        assertEquals(EventDrivenBlockUpdateListener.FurnaceTarget.NONE,
                EventDrivenBlockUpdateListener.furnaceTarget(Material.CHEST));
        assertEquals(EventDrivenBlockUpdateListener.FurnaceTarget.NONE,
                EventDrivenBlockUpdateListener.furnaceTarget(null));

        assertEquals(EventDrivenBlockUpdateListener.BeeTarget.HIVE,
                EventDrivenBlockUpdateListener.beeTarget(Material.BEEHIVE));
        assertEquals(EventDrivenBlockUpdateListener.BeeTarget.NEST,
                EventDrivenBlockUpdateListener.beeTarget(Material.BEE_NEST));
        assertEquals(EventDrivenBlockUpdateListener.BeeTarget.NONE,
                EventDrivenBlockUpdateListener.beeTarget(Material.HONEY_BLOCK));
        assertEquals(EventDrivenBlockUpdateListener.BeeTarget.NONE,
                EventDrivenBlockUpdateListener.beeTarget(null));
        assertNull(EventDrivenBlockUpdateListener.inventoryBlock(null));
    }

    private static Class<?> findDeclaredClass(Class<?> owner, String simpleName) {
        for (Class<?> candidate : owner.getDeclaredClasses()) {
            if (candidate.getSimpleName().equals(simpleName)) {
                return candidate;
            }
        }
        throw new AssertionError("Missing " + owner.getSimpleName() + "." + simpleName);
    }

    private static void assertRegistered(Class<?> type, String methodName, Class<?> eventType) throws Exception {
        Method method = type.getDeclaredMethod(methodName, eventType);
        assertNotNull(method.getAnnotation(EventHandler.class), type.getSimpleName() + "." + methodName);
    }

    private static void assertNotRegistered(Class<?> type, String methodName, Class<?> eventType) throws Exception {
        Method method = type.getDeclaredMethod(methodName, eventType);
        assertNull(method.getAnnotation(EventHandler.class), type.getSimpleName() + "." + methodName);
    }
}
