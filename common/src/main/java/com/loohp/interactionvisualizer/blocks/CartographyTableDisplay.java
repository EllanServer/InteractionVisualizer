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
import com.loohp.interactionvisualizer.entityholders.Item.RenderMode;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.managers.InteractionSessionCoordinator;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.utils.VanishUtils;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.CartographyInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

public class CartographyTableDisplay extends VisualizerInteractDisplay implements Listener {

    public static final EntryKey KEY = new EntryKey("cartography_table");

    public Map<Block, Map<String, Object>> openedCTable = new HashMap<>();
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
            if (!(player.getOpenInventory().getTopInventory() instanceof CartographyInventory)) {
                return;
            }

            Block block = player.getTargetBlockExact(7, FluidCollisionMode.NEVER);
            if (block == null || !block.getType().equals(Material.CARTOGRAPHY_TABLE)) {
                return;
            }

            playermap.put(player, block);
        }

        InventoryView view = player.getOpenInventory();
        Block block = playermap.get(player);

        if (!openedCTable.containsKey(block)) {
            Map<String, Object> map = new HashMap<>();
            map.put("Player", player);
            map.put("Item", "N/A");
            openedCTable.put(block, map);
        }
        Map<String, Object> map = openedCTable.get(block);

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
        ItemStack output = view.getItem(2);
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

        if (block.getRelative(BlockFace.UP).getType().isSolid()) {
            if (map.get("Item") instanceof Item item) {
                DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
                map.put("Item", "N/A");
            }
            return;
        }

        if (map.get("Item") instanceof String) {
            if (itemstack != null) {
                Location location = block.getLocation().clone().add(0.5, 1.03125, 0.5);
                location.setPitch(-90.0F);
                Item item = new Item(location, RenderMode.FRAME);
                item.setItemStack(itemstack);
                item.setSilent(true);
                map.put("Item", item);
                DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMSTAND, KEY), item);
                DisplayManager.updateItem(item);
            } else {
                map.put("Item", "N/A");
            }
        } else {
            Item item = (Item) map.get("Item");
            if (itemstack != null) {
                if (!item.getItemStack().equals(itemstack)) {
                    item.setItemStack(itemstack);
                    DisplayManager.updateItem(item);
                }
            } else {
                map.put("Item", "N/A");
                DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUseCartographyTable(InventoryClickEvent event) {
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
    public void onDragCartographyTable(InventoryDragEvent event) {
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
    public void onCloseCartographyTable(InventoryCloseEvent event) {
        cleanupSession((Player) event.getPlayer());
    }

    private boolean isSessionValid(Player player) {
        Block block = playermap.get(player);
        if (block == null) {
            return false;
        }
        Map<String, Object> map = openedCTable.get(block);
        return player.isOnline() && !GameMode.SPECTATOR.equals(player.getGameMode())
                && map != null && player.equals(map.get("Player"))
                && block.getWorld().equals(player.getWorld())
                && block.getType() == Material.CARTOGRAPHY_TABLE
                && player.getOpenInventory().getTopInventory() instanceof CartographyInventory;
    }

    private void cleanupSession(Player player) {
        Block block = playermap.remove(player);
        if (block == null) {
            return;
        }
        Map<String, Object> map = openedCTable.get(block);
        if (map == null || !player.equals(map.get("Player"))) {
            return;
        }

        if (map.get("Item") instanceof Item item) {
            DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
        }
        openedCTable.remove(block, map);
    }

}
