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

import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import com.loohp.interactionvisualizer.api.VisualizerInteractDisplay;
import com.loohp.interactionvisualizer.listeners.Events;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import io.papermc.paper.event.player.PlayerLoomPatternSelectEvent;
import io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryRefreshEventTest {

    @Test
    void inventoryMutationEventsQueuePostCommitRefreshes() throws Exception {
        assertHandler("onInventoryOpen", InventoryOpenEvent.class, true);
        assertHandler("onInventoryClick", InventoryClickEvent.class, true);
        assertHandler("onInventoryDrag", InventoryDragEvent.class, true);
        assertHandler("onPrepareInventoryResult", PrepareResultEvent.class, false);
        assertHandler("onPrepareItemCraft", PrepareItemCraftEvent.class, false);
        assertHandler("onStonecutterRecipeSelect", PlayerStonecutterRecipeSelectEvent.class, true);
        assertHandler("onLoomPatternSelect", PlayerLoomPatternSelectEvent.class, true);
    }

    @Test
    void coalescesRefreshesWhilePreservingTheOneTickOpenCallback() {
        UUID playerId = UUID.randomUUID();
        try {
            assertEquals(1L, TaskManager.INVENTORY_OPEN_PROCESS_DELAY_TICKS);
            assertEquals(2L, TaskManager.INVENTORY_PROCESS_DELAY_TICKS);
            assertTrue(TaskManager.markInventoryRefreshQueued(playerId));
            assertFalse(TaskManager.markInventoryRefreshQueued(playerId));

            assertTrue(TaskManager.markInventoryOpenProcessQueued(playerId),
                    "an open callback must replace an older native refresh");
            assertFalse(TaskManager.markInventoryRefreshQueued(playerId),
                    "the pending open callback already refreshes native displays");
            assertFalse(TaskManager.markInventoryOpenProcessQueued(playerId));

            TaskManager.clearPendingInventoryProcess(playerId);
            assertTrue(TaskManager.markInventoryRefreshQueued(playerId));
        } finally {
            TaskManager.clearPendingInventoryProcess(playerId);
        }
    }

    @Test
    void nativeRefreshesDoNotInvokeCustomOpenCallbacks() {
        VisualizerInteractDisplay nativeDisplay = display(new EntryKey("native_test"));
        VisualizerInteractDisplay customDisplay = display(new EntryKey("example", "custom_test"));

        assertTrue(TaskManager.isNativeInventoryDisplay(nativeDisplay));
        assertFalse(TaskManager.isNativeInventoryDisplay(customDisplay));
    }

    @Test
    void filtersBottomInventoryMutationsThatCannotAffectTheTop() throws Exception {
        Method method = Events.class.getDeclaredMethod(
                "affectsTopInventory", int.class, int.class, InventoryAction.class);
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(null, 9, 0, InventoryAction.PICKUP_ALL));
        assertFalse((boolean) method.invoke(null, 9, 12, InventoryAction.PICKUP_ALL));
        assertTrue((boolean) method.invoke(null, 9, 12, InventoryAction.MOVE_TO_OTHER_INVENTORY));
        assertTrue((boolean) method.invoke(null, 9, 12, InventoryAction.COLLECT_TO_CURSOR));
        assertFalse((boolean) method.invoke(null, 9, -999, InventoryAction.DROP_ALL_CURSOR));

        Method dragMethod = Events.class.getDeclaredMethod(
                "affectsTopInventory", int.class, Iterable.class);
        dragMethod.setAccessible(true);
        assertTrue((boolean) dragMethod.invoke(null, 9, List.of(8, 12)));
        assertFalse((boolean) dragMethod.invoke(null, 9, List.of(9, 12)));
    }

    private static void assertHandler(String methodName, Class<? extends Event> eventType,
                                      boolean ignoreCancelled) throws Exception {
        Method method = Events.class.getDeclaredMethod(methodName, eventType);
        EventHandler annotation = method.getAnnotation(EventHandler.class);
        assertEquals(EventPriority.MONITOR, annotation.priority(), methodName);
        assertEquals(ignoreCancelled, annotation.ignoreCancelled(), methodName);
    }

    private static VisualizerInteractDisplay display(EntryKey key) {
        return new VisualizerInteractDisplay() {
            @Override
            public EntryKey key() {
                return key;
            }

            @Override
            public void process(Player player) {
            }
        };
    }
}
