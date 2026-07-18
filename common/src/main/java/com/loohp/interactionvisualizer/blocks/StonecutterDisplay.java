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

package com.loohp.interactionvisualizer.blocks;

import com.loohp.interactionvisualizer.InteractionVisualizer;
import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI;
import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI.Modules;
import com.loohp.interactionvisualizer.api.VisualizerInteractDisplay;
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.managers.InteractionSessionCoordinator;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.utils.InventoryUtils;
import com.loohp.interactionvisualizer.utils.VanishUtils;
import com.loohp.interactionvisualizer.scheduler.ScheduledRunnable;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.StonecutterInventory;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;

public class StonecutterDisplay extends VisualizerInteractDisplay implements Listener {

    public static final EntryKey KEY = new EntryKey("stonecutter");

    public Map<Block, Map<String, Object>> openedStonecutter = new HashMap<>();
    public Map<Player, Block> playermap = new HashMap<>();

    @Override
    public EntryKey key() {
        return KEY;
    }

    @Override
    public ScheduledTask run() {
        InteractionSessionCoordinator.register(this, playermap::keySet,
                this::isSessionValid, this::cleanupSession);
        return null;
    }

    @Override
    public void process(Player player) {
        if (VanishUtils.isVanished(player)) {
            return;
        }
        if (!playermap.containsKey(player)) {
            if (GameMode.SPECTATOR.equals(player.getGameMode())) {
                return;
            }
            if (!(player.getOpenInventory().getTopInventory() instanceof StonecutterInventory)) {
                return;
            }

            Block block = player.getTargetBlockExact(7, FluidCollisionMode.NEVER);
            if (block == null || !block.getType().equals(Material.STONECUTTER)) {
                return;
            }

            playermap.put(player, block);
        }

        InventoryView view = player.getOpenInventory();
        Block block = playermap.get(player);
        Location loc = block.getLocation();
        if (!openedStonecutter.containsKey(block)) {
            Map<String, Object> map = new HashMap<>();
            map.put("Player", player);
            map.put("Item", "N/A");
            openedStonecutter.put(block, map);
        }
        Map<String, Object> map = openedStonecutter.get(block);

        if (!map.get("Player").equals(player)) {
            return;
        }
        InteractionSessionCoordinator.touch();

        ItemStack input = view.getItem(0);
        if (input != null) {
            if (input.getType().equals(Material.AIR)) {
                input = null;
            }
        }
        ItemStack output = view.getItem(1);
        if (output != null) {
            if (output.getType().equals(Material.AIR)) {
                output = null;
            }
        }

        ItemStack itemstack = null;
        if (output == null) {
            if (input != null) {
                itemstack = input;
            }
        } else {
            itemstack = output;
        }

        if (itemstack != null) {
            ItemStack itempar = itemstack.clone();
            ScheduledTask task = new ScheduledRunnable() {
                public void run() {
                    player.getWorld().spawnParticle(Particle.ITEM, loc.clone().add(0.5, 0.7, 0.5), 25, 0.1, 0.1, 0.1, 0.1, itempar);
                }
            }.runTaskTimer(InteractionVisualizer.plugin, 0, 1);
            new ScheduledRunnable() {
                public void run() {
                    task.cancel();
                }
            }.runTaskLater(InteractionVisualizer.plugin, 4);
        }

        Item item = null;
        if (map.get("Item") instanceof String) {
            if (itemstack != null) {
                item = new Item(loc.clone().add(0.5, 0.75, 0.5));
                item.setItemStack(itemstack);
                item.setVelocity(new Vector(0, 0, 0));
                item.setPickupDelay(32767);
                item.setGravity(false);
                map.put("Item", item);
                DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY), item);
                DisplayManager.updateItem(item);
            } else {
                map.put("Item", "N/A");
            }
        } else {
            item = (Item) map.get("Item");
            if (itemstack != null) {
                if (!item.getItemStack().equals(itemstack)) {
                    item.setItemStack(itemstack);
                    DisplayManager.updateItem(item);
                }
                item.setPickupDelay(32767);
                item.setGravity(false);
            } else {
                map.put("Item", "N/A");
                DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStonecutter(InventoryClickEvent event) {
        if (VanishUtils.isVanished((Player) event.getWhoClicked())) {
            return;
        }
        if (event.isCancelled()) {
            return;
        }
        if (event.getRawSlot() != 1) {
            return;
        }
        if (event.getCurrentItem() == null) {
            return;
        }
        if (event.getCurrentItem().getType().equals(Material.AIR)) {
            return;
        }
        if (event.getCursor() != null) {
            if (!event.getCursor().getType().equals(Material.AIR)) {
                if (event.getCursor().getAmount() >= event.getCursor().getType().getMaxStackSize()) {
                    return;
                }
            }
        }
        if (event.isShiftClick()) {
            if (!InventoryUtils.stillHaveSpace(event.getWhoClicked().getInventory(), event.getView().getItem(1).getType())) {
                return;
            }
        }
        int hotbarSlot = event.getHotbarButton();
        if (hotbarSlot >= 0 && event.getAction().equals(InventoryAction.HOTBAR_SWAP)) {
            if (event.getWhoClicked().getInventory().getItem(hotbarSlot) != null && !event.getWhoClicked().getInventory().getItem(hotbarSlot).getType().equals(Material.AIR)) {
                return;
            }
        }

        if (!playermap.containsKey((Player) event.getWhoClicked())) {
            return;
        }

        Block block = playermap.get((Player) event.getWhoClicked());

        if (!openedStonecutter.containsKey(block)) {
            return;
        }

        Map<String, Object> map = openedStonecutter.get(block);
        if (!map.get("Player").equals(event.getWhoClicked())) {
            return;
        }

        ItemStack itemstack = event.getCurrentItem().clone();
        Player player = (Player) event.getWhoClicked();
        InventoryView view = event.getView();

        if (map.get("Item") instanceof String) {
            Item result = new Item(block.getLocation().clone().add(0.5, 0.75, 0.5));
            result.setItemStack(itemstack);
            map.put("Item", result);
            DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY), result);
        }
        Item item = (Item) map.get("Item");

        Inventory before = Bukkit.createInventory(null, 9);
        before.setItem(0, InventoryUtils.cloneItem(view.getItem(0)));

        Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory() != view) {
                return;
            }

            Inventory after = Bukkit.createInventory(null, 9);
            after.setItem(0, InventoryUtils.cloneItem(view.getItem(0)));

            if (InventoryUtils.compareContents(before, after)) {
                return;
            }

            openedStonecutter.remove(block);

            item.setItemStack(itemstack);
            item.setLocked(true);
            DisplayManager.updateItem(item);
            DisplayManager.collectItem(item, player);
        }, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUseStonecutter(InventoryClickEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (!playermap.containsKey((Player) event.getWhoClicked())) {
            return;
        }

        if (event.getRawSlot() >= 0 && event.getRawSlot() <= 1) {
            DisplayManager.sendHandMovement(InteractionVisualizerAPI.getPlayers(), (Player) event.getWhoClicked());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragStonecutter(InventoryDragEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (!playermap.containsKey((Player) event.getWhoClicked())) {
            return;
        }

        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot <= 1) {
                DisplayManager.sendHandMovement(InteractionVisualizerAPI.getPlayers(), (Player) event.getWhoClicked());
                break;
            }
        }
    }

    @EventHandler
    public void onCloseStonecutter(InventoryCloseEvent event) {
        cleanupSession((Player) event.getPlayer());
    }

    private boolean isSessionValid(Player player) {
        Block block = playermap.get(player);
        if (block == null) {
            return false;
        }
        Map<String, Object> map = openedStonecutter.get(block);
        return player.isOnline() && !GameMode.SPECTATOR.equals(player.getGameMode())
                && map != null && player.equals(map.get("Player"))
                && block.getWorld().equals(player.getWorld())
                && block.getType() == Material.STONECUTTER
                && player.getOpenInventory().getTopInventory() instanceof StonecutterInventory;
    }

    private void cleanupSession(Player player) {
        Block block = playermap.remove(player);
        if (block == null) {
            return;
        }
        Map<String, Object> map = openedStonecutter.get(block);
        if (map == null || !player.equals(map.get("Player"))) {
            return;
        }

        if (map.get("Item") instanceof Item entity) {
            DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), entity);
        }
        openedStonecutter.remove(block, map);
    }

}
