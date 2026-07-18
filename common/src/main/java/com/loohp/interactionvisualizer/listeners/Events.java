/*
 * This file is part of InteractionVisualizer.
 *
 * Copyright (C) 2025. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2025. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.loohp.interactionvisualizer.listeners;

import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import com.loohp.interactionvisualizer.InteractionVisualizer;
import com.loohp.interactionvisualizer.managers.TaskManager;
import com.loohp.interactionvisualizer.managers.InteractionSessionCoordinator;
import io.papermc.paper.event.player.PlayerLoomPatternSelectEvent;
import io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent;
import org.bukkit.World;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

public class Events implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player
                && TaskManager.hasInventoryDisplays(event.getInventory().getType())) {
            TaskManager.processOpenInventory(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (affectsTopInventory(event)) {
            queueNativeInventoryRefresh(event.getWhoClicked(), event.getView().getTopInventory().getType());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (affectsTopInventory(event)) {
            queueNativeInventoryRefresh(event.getWhoClicked(), event.getView().getTopInventory().getType());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPrepareInventoryResult(PrepareResultEvent event) {
        queueNativeInventoryRefresh(event.getView().getPlayer(), event.getInventory().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPrepareItemCraft(PrepareItemCraftEvent event) {
        queueNativeInventoryRefresh(event.getView().getPlayer(), event.getInventory().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStonecutterRecipeSelect(PlayerStonecutterRecipeSelectEvent event) {
        queueNativeInventoryRefresh(event.getPlayer(), event.getStonecutterInventory().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLoomPatternSelect(PlayerLoomPatternSelectEvent event) {
        queueNativeInventoryRefresh(event.getPlayer(), event.getLoomInventory().getType());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        TaskManager.clearPendingInventoryProcess(event.getPlayer().getUniqueId());
        InteractionSessionCoordinator.invalidate(event.getPlayer());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        TaskManager.clearPendingInventoryProcess(event.getPlayer().getUniqueId());
        InteractionSessionCoordinator.invalidate(event.getPlayer());
    }

    private static void queueNativeInventoryRefresh(HumanEntity entity, InventoryType type) {
        if (entity instanceof Player player && TaskManager.hasNativeInventoryDisplays(type)) {
            TaskManager.refreshOpenInventory(player);
        }
    }

    private static boolean affectsTopInventory(InventoryClickEvent event) {
        return affectsTopInventory(event.getView().getTopInventory().getSize(),
                event.getRawSlot(), event.getAction());
    }

    static boolean affectsTopInventory(int topSize, int rawSlot, InventoryAction action) {
        if (rawSlot >= 0 && rawSlot < topSize) {
            return true;
        }
        return action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.COLLECT_TO_CURSOR;
    }

    private static boolean affectsTopInventory(InventoryDragEvent event) {
        return affectsTopInventory(event.getView().getTopInventory().getSize(), event.getRawSlots());
    }

    static boolean affectsTopInventory(int topSize, Iterable<Integer> rawSlots) {
        for (int slot : rawSlots) {
            if (slot >= 0 && slot < topSize) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        int range = Math.max(32, world.getSendViewDistance() * 16);
        InteractionVisualizer.playerTrackingRange.put(world, range);
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        InteractionVisualizer.playerTrackingRange.remove(event.getWorld());
    }

}
