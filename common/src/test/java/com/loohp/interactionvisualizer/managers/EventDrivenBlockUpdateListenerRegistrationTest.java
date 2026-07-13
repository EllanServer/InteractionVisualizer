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
import com.loohp.interactionvisualizer.blocks.BeeNestDisplay;
import com.loohp.interactionvisualizer.blocks.BlastFurnaceDisplay;
import com.loohp.interactionvisualizer.blocks.FurnaceDisplay;
import com.loohp.interactionvisualizer.blocks.SmokerDisplay;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityEnterBlockEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class EventDrivenBlockUpdateListenerRegistrationTest {

    @Test
    void eventDrivenOnlyHandlersAreAbsentFromAlwaysRegisteredListeners() throws Exception {
        assertNotRegistered(FurnaceDisplay.class, "onFurnaceBurn", FurnaceBurnEvent.class);
        assertNotRegistered(BlastFurnaceDisplay.class, "onBlastFurnaceBurn", FurnaceBurnEvent.class);
        assertNotRegistered(SmokerDisplay.class, "onSmokerBurn", FurnaceBurnEvent.class);
        assertNotRegistered(BeeHiveDisplay.class, "onAffectedBlockPlace", BlockPlaceEvent.class);
        assertNotRegistered(SmokerDisplay.class, "onRemoveSmoker", TileEntityRemovedEvent.class);
        assertNotRegistered(TileEntityManager.class, "onChunkLoad", ChunkLoadEvent.class);
    }

    @Test
    void legacyHandlersRemainOnAlwaysRegisteredListeners() throws Exception {
        assertRegistered(FurnaceDisplay.class, "onUseFurnace", InventoryClickEvent.class);
        assertRegistered(BeeHiveDisplay.class, "onBeeEnterBeehive", EntityEnterBlockEvent.class);
        assertRegistered(BeeNestDisplay.class, "onBeeEnterBeenest", EntityEnterBlockEvent.class);
        assertRegistered(TileEntityManager.class, "onPlayerMove", PlayerMoveEvent.class);
    }

    @Test
    void conditionalListenerOwnsTheEventDrivenSurface() throws Exception {
        // Paper 26.1.2 emits FurnaceBurnEvent for every processing furnace on
        // every tick. It is a level signal, not an invalidation edge; routing it
        // would permanently saturate each furnace scheduler's dirty budget.
        assertNoRegisteredHandler(EventDrivenBlockUpdateListener.class, FurnaceBurnEvent.class);
        assertRegistered(EventDrivenBlockUpdateListener.class, "onFurnaceStartSmelt", FurnaceStartSmeltEvent.class);
        assertRegistered(EventDrivenBlockUpdateListener.class, "onFurnaceSmelt", FurnaceSmeltEvent.class);
        assertRegistered(EventDrivenBlockUpdateListener.class, "onFurnaceExtract", FurnaceExtractEvent.class);
        assertRegistered(EventDrivenBlockUpdateListener.class, "onInventoryMoveItem", InventoryMoveItemEvent.class);
        assertRegistered(EventDrivenBlockUpdateListener.class, "onAffectedBlockPlace", BlockPlaceEvent.class);
        assertRegistered(EventDrivenBlockUpdateListener.class, "onBeeEnterBlock", EntityEnterBlockEvent.class);
        assertRegistered(EventDrivenBlockUpdateListener.class, "onEntityChangeBlock", EntityChangeBlockEvent.class);
        assertRegistered(EventDrivenBlockUpdateListener.class, "onBeeRelevantInteract", PlayerInteractEvent.class);
        assertRegistered(EventDrivenBlockUpdateListener.class, "onAffectedPistonExtend", BlockPistonExtendEvent.class);
        assertRegistered(EventDrivenBlockUpdateListener.class, "onAffectedPistonRetract", BlockPistonRetractEvent.class);
        assertRegistered(EventDrivenBlockUpdateListener.class, "onAffectedRedstone", BlockRedstoneEvent.class);
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

    private static void assertNoRegisteredHandler(Class<?> type, Class<?> eventType) {
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            boolean catchesEvent = parameters.length == 1 && parameters[0].isAssignableFrom(eventType);
            assertFalse(catchesEvent && method.getAnnotation(EventHandler.class) != null,
                    type.getSimpleName() + "." + method.getName());
        }
    }
}
