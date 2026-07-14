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
import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.objectholders.LightType;
import com.loohp.interactionvisualizer.utils.InventoryUtils;
import com.loohp.interactionvisualizer.utils.MaterialUtils;
import com.loohp.interactionvisualizer.utils.MaterialUtils.MaterialMode;
import com.loohp.interactionvisualizer.utils.VanishUtils;
import com.loohp.interactionvisualizer.scheduler.ScheduledRunnable;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
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
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class CraftingTableDisplay extends VisualizerInteractDisplay implements Listener {

    public static final EntryKey KEY = new EntryKey("crafting_table");

    public Map<Block, Map<String, Object>> openedBenches = new HashMap<>();
    public Map<Player, Block> playermap = new HashMap<>();

    @Override
    public EntryKey key() {
        return KEY;
    }

    @Override
    public ScheduledTask run() {
        return new ScheduledRunnable() {
            public void run() {
                Iterator<Block> itr = openedBenches.keySet().iterator();
                int count = 0;
                int maxper = (int) Math.ceil((double) openedBenches.size() / (double) 5);
                int delay = 1;
                while (itr.hasNext()) {
                    count++;
                    if (count > maxper) {
                        count = 0;
                        delay++;
                    }
                    Block block = itr.next();
                    new ScheduledRunnable() {
                        public void run() {
                            if (!openedBenches.containsKey(block)) {
                                return;
                            }
                            Map<String, Object> map = openedBenches.get(block);

                            Player player = (Player) map.get("Player");
                            if (!GameMode.SPECTATOR.equals(player.getGameMode())) {
                                if (player.getOpenInventory() != null) {
                                    if (player.getOpenInventory().getTopInventory() != null) {
                                        if (player.getOpenInventory().getTopInventory().getLocation().getBlock().getType() == Material.CRAFTING_TABLE) {
                                            return;
                                        }
                                    }
                                }
                            }

                            for (int i = 0; i <= 9; i++) {
                                if (!(map.get(String.valueOf(i)) instanceof String)) {
                                    Object entity = map.get(String.valueOf(i));
                                    if (i == 5) {
                                        InteractionVisualizer.lightManager.deleteLight(((Item) entity).getLocation());
                                    }
                                    if (entity instanceof Item) {
                                        DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), (Item) entity);
                                    }
                                }
                            }
                            openedBenches.remove(block);
                            playermap.remove(player, block);
                        }
                    }.runTaskLater(InteractionVisualizer.plugin, delay, block.getLocation());
                }
            }
        }.runTaskTimer(InteractionVisualizer.plugin, 0, 5);
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
            if (player.getOpenInventory().getTopInventory().getLocation() == null) {
                return;
            }
            if (player.getOpenInventory().getTopInventory().getLocation().getBlock() == null) {
                return;
            }
            if (player.getOpenInventory().getTopInventory().getLocation().getBlock().getType() != Material.CRAFTING_TABLE) {
                return;
            }
            InventoryView view = player.getOpenInventory();
            Block block = view.getTopInventory().getLocation().getBlock();
            playermap.put(player, block);
        }

        InventoryView view = player.getOpenInventory();
        Block block = playermap.get(player);
        Location loc = block.getLocation();

        if (!openedBenches.containsKey(block)) {
            Map<String, Object> map = new HashMap<>();
            map.put("Player", player);
            map.put("0", "N/A");
            map.putAll(spawnDisplayEntitys(player, block));
            openedBenches.put(block, map);
        }

        Map<String, Object> map = openedBenches.get(block);

        if (!map.get("Player").equals(player)) {
            return;
        }
        ItemStack[] items = new ItemStack[] {
                view.getItem(1),
                view.getItem(2),
                view.getItem(3),
                view.getItem(4),
                view.getItem(5),
                view.getItem(6),
                view.getItem(7),
                view.getItem(8),
                view.getItem(9)
        };

        ItemStack itemstack = view.getItem(0);
        if (itemstack != null && itemstack.getType().equals(Material.AIR)) {
            itemstack = null;
        }
        if (map.get("0") instanceof String) {
            if (itemstack != null) {
                Item item = new Item(loc.clone().add(0.5, 1.2, 0.5));
                item.setItemStack(itemstack);
                item.setVelocity(new Vector(0, 0, 0));
                item.setPickupDelay(32767);
                item.setGravity(false);
                map.put("0", item);
                DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY), item);
                DisplayManager.updateItem(item);
            } else {
                map.put("0", "N/A");
            }
        } else {
            Item item = (Item) map.get("0");
            if (itemstack != null) {
                if (!item.getItemStack().equals(itemstack)) {
                    item.setItemStack(itemstack);
                    DisplayManager.updateItem(item);
                }
            } else {
                map.put("0", "N/A");
                DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
            }
        }
        for (int i = 0; i < 9; i++) {
            Item stand = (Item) map.get(String.valueOf(i + 1));
            ItemStack itemStack = items[i];
            if (itemStack == null || itemStack.getType().equals(Material.AIR)) {
                itemStack = ItemStack.empty();
            }
            if (!itemStack.isEmpty()) {
                Item.RenderMode renderMode = renderMode(MaterialUtils.getMaterialType(itemStack));
                boolean changed = renderMode != standMode(stand);
                if (changed) {
                    toggleStandMode(stand, renderMode);
                }
                if (!itemStack.equals(stand.getItemStack())) {
                    changed = true;
                    stand.setItemStack(itemStack);
                }
                if (changed) {
                    DisplayManager.updateItem(stand);
                }
            } else {
                if (!stand.getItemStack().isEmpty()) {
                    stand.setItemStack(ItemStack.empty());
                    DisplayManager.updateItem(stand);
                }
            }
        }
        Location loc1 = ((Item) map.get("5")).getLocation();
        InteractionVisualizer.lightManager.deleteLight(loc1);
        int skylight = loc1.getBlock().getRelative(BlockFace.UP).getLightFromSky();
        int blocklight = loc1.getBlock().getRelative(BlockFace.UP).getLightFromBlocks() - 1;
        blocklight = Math.max(blocklight, 0);
        if (skylight > 0) {
            InteractionVisualizer.lightManager.createLight(loc1, skylight, LightType.SKY);
        }
        if (blocklight > 0) {
            InteractionVisualizer.lightManager.createLight(loc1, blocklight, LightType.BLOCK);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraft(InventoryClickEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (VanishUtils.isVanished((Player) event.getWhoClicked())) {
            return;
        }
        if (event.getRawSlot() != 0) {
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
            if (!InventoryUtils.stillHaveSpace(event.getWhoClicked().getInventory(), event.getView().getItem(0).getType())) {
                return;
            }
        }
        if (event.getAction().equals(InventoryAction.HOTBAR_SWAP)) {
            int hotbarSlot = event.getHotbarButton();
            if (hotbarSlot < 0 || (event.getWhoClicked().getInventory().getItem(hotbarSlot) != null && !event.getWhoClicked().getInventory().getItem(hotbarSlot).getType().equals(Material.AIR))) {
                return;
            }
        }

        if (!playermap.containsKey((Player) event.getWhoClicked())) {
            return;
        }

        Block block = playermap.get((Player) event.getWhoClicked());

        if (!openedBenches.containsKey(block)) {
            return;
        }

        Map<String, Object> map = openedBenches.get(block);
        if (!map.get("Player").equals(event.getWhoClicked())) {
            return;
        }

        ItemStack itemstack = event.getCurrentItem().clone();
        Location loc = block.getLocation();
        Player player = (Player) event.getWhoClicked();
        InventoryView view = event.getView();

        Item item;
        if (map.get("0") instanceof String) {
            item = new Item(block.getLocation().clone().add(0.5, 1.2, 0.5));
            item.setItemStack(itemstack);
            map.put("0", item);
            DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY), item);
        } else {
            item = (Item) map.get("0");
        }
        Item slot1 = (Item) map.get("1");
        Item slot2 = (Item) map.get("2");
        Item slot3 = (Item) map.get("3");
        Item slot4 = (Item) map.get("4");
        Item slot5 = (Item) map.get("5");
        Item slot6 = (Item) map.get("6");
        Item slot7 = (Item) map.get("7");
        Item slot8 = (Item) map.get("8");
        Item slot9 = (Item) map.get("9");

        Inventory before = Bukkit.createInventory(null, 9);
        for (int i = 1; i < 10; i++) {
            before.setItem(i - 1, InventoryUtils.cloneItem(view.getItem(i)));
        }

        Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory() != view) {
                return;
            }

            Inventory after = Bukkit.createInventory(null, 9);
            for (int i = 1; i < 10; i++) {
                after.setItem(i - 1, InventoryUtils.cloneItem(view.getItem(i)));
            }

            if (InventoryUtils.compareContents(before, after)) {
                return;
            }

            openedBenches.remove(block);

            float yaw = getCardinalDirection(player);
            Vector vector = new Location(slot8.getWorld(), slot8.getLocation().getX(), slot8.getLocation().getY(), slot8.getLocation().getZ(), yaw, 0).getDirection().normalize();
            slot1.teleport(slot1.getLocation().add(rotateVectorAroundY(vector.clone(), 135).multiply(0.2828)));
            slot2.teleport(slot2.getLocation().add(rotateVectorAroundY(vector.clone(), 180).multiply(0.2)));
            slot3.teleport(slot3.getLocation().add(rotateVectorAroundY(vector.clone(), 225).multiply(0.2828)));
            slot4.teleport(slot4.getLocation().add(rotateVectorAroundY(vector.clone(), 90).multiply(0.2)));

            slot6.teleport(slot6.getLocation().add(rotateVectorAroundY(vector.clone(), -90).multiply(0.2)));
            slot7.teleport(slot7.getLocation().add(rotateVectorAroundY(vector.clone(), 45).multiply(0.2828)));
            slot8.teleport(slot8.getLocation().add(vector.clone().multiply(0.2)));
            slot9.teleport(slot9.getLocation().add(rotateVectorAroundY(vector.clone(), -45).multiply(0.2828)));

            DisplayManager.updateItem(slot1);
            DisplayManager.updateItem(slot2);
            DisplayManager.updateItem(slot3);
            DisplayManager.updateItem(slot4);
            DisplayManager.updateItem(slot5);
            DisplayManager.updateItem(slot6);
            DisplayManager.updateItem(slot7);
            DisplayManager.updateItem(slot8);
            DisplayManager.updateItem(slot9);

            item.setItemStack(itemstack, true);
            item.setLocked(true);
            slot1.setLocked(true);
            slot2.setLocked(true);
            slot3.setLocked(true);
            slot4.setLocked(true);
            slot5.setLocked(true);
            slot6.setLocked(true);
            slot7.setLocked(true);
            slot8.setLocked(true);
            slot9.setLocked(true);

            Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
                for (Player each : InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY)) {
                    each.spawnParticle(Particle.CLOUD, loc.clone().add(0.5, 1.1, 0.5), 10, 0.05, 0.05, 0.05, 0.05);
                }
            }, 6);

            Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
                DisplayManager.updateItem(item);
                DisplayManager.collectItem(item, player);

                Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
                    DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), slot1);
                    DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), slot2);
                    DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), slot3);
                    DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), slot4);
                    DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), slot5);
                    Map<String, Object> current = openedBenches.get(block);
                    if (current == null || current.get("5") == slot5) {
                        InteractionVisualizer.lightManager.deleteLight(slot5.getLocation());
                    }
                    DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), slot6);
                    DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), slot7);
                    DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), slot8);
                    DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), slot9);
                }, 8);
            }, 10);
        }, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUseCraftingBench(InventoryClickEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (!playermap.containsKey((Player) event.getWhoClicked())) {
            return;
        }

        if (event.getRawSlot() >= 0 && event.getRawSlot() <= 9) {
            DisplayManager.sendHandMovement(InteractionVisualizerAPI.getPlayers(), (Player) event.getWhoClicked());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragCraftingBench(InventoryDragEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (!playermap.containsKey((Player) event.getWhoClicked())) {
            return;
        }

        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot <= 9) {
                DisplayManager.sendHandMovement(InteractionVisualizerAPI.getPlayers(), (Player) event.getWhoClicked());
                break;
            }
        }
    }

    @EventHandler
    public void onCloseCraftingBench(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        Block block = playermap.remove(player);
        if (block == null) {
            return;
        }

        Map<String, Object> map = openedBenches.get(block);
        if (map == null || !map.get("Player").equals(player)) {
            return;
        }

        for (int i = 0; i <= 9; i++) {
            if (!(map.get(String.valueOf(i)) instanceof String)) {
                Object entity = map.get(String.valueOf(i));
                if (entity instanceof Item) {
                    Item item = (Item) entity;
                    DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
                    int finalI = i;
                    new ScheduledRunnable() {
                        public void run() {
                            if (finalI == 5) {
                                InteractionVisualizer.lightManager.deleteLight(item.getLocation());
                            }
                        }
                    }.runTaskLater(InteractionVisualizer.plugin, 20);
                }
            }
        }
        openedBenches.remove(block);
    }

    public Item.RenderMode standMode(Item stand) {
        return stand.getRenderMode();
    }

    private static Item.RenderMode renderMode(MaterialMode mode) {
        return switch (mode) {
            case ITEM -> Item.RenderMode.ITEM;
            case BLOCK -> Item.RenderMode.BLOCK;
            case LOWBLOCK -> Item.RenderMode.LOW_BLOCK;
            case TOOL -> Item.RenderMode.TOOL;
            case STANDING -> Item.RenderMode.STANDING;
        };
    }

    public void toggleStandMode(Item stand, Item.RenderMode mode) {
        if (stand.getRenderMode() == mode) {
            return;
        }

        switch (stand.getRenderMode()) {
            case BLOCK -> {
                stand.setRenderMode(Item.RenderMode.ITEM);
                stand.setRotation(stand.getLocation().getYaw() - 45, stand.getLocation().getPitch());
                stand.teleport(stand.getLocation().add(0.0, -0.084, 0.0));
                stand.teleport(stand.getLocation().add(rotateVectorAroundY(stand.getLocation().getDirection().normalize().multiply(-0.102), -90)));
                stand.teleport(stand.getLocation().add(stand.getLocation().getDirection().normalize().multiply(-0.14)));
            }
            case LOW_BLOCK -> {
                stand.setRenderMode(Item.RenderMode.ITEM);
                stand.setRotation(stand.getLocation().getYaw() - 45, stand.getLocation().getPitch());
                stand.teleport(stand.getLocation().add(0.0, -0.02, 0.0));
                stand.teleport(stand.getLocation().add(rotateVectorAroundY(stand.getLocation().getDirection().normalize().multiply(-0.09), -90)));
                stand.teleport(stand.getLocation().add(stand.getLocation().getDirection().normalize().multiply(-0.15)));
            }
            case TOOL -> {
                stand.setRenderMode(Item.RenderMode.ITEM);
                stand.teleport(stand.getLocation().add(rotateVectorAroundY(stand.getLocation().getDirection().normalize().multiply(0.3), -90)));
                stand.teleport(stand.getLocation().add(stand.getLocation().getDirection().normalize().multiply(0.1)));
                stand.teleport(stand.getLocation().add(0, 0.26, 0));
            }
            case STANDING -> {
                stand.setRenderMode(Item.RenderMode.ITEM);
                stand.teleport(stand.getLocation().add(rotateVectorAroundY(stand.getLocation().getDirection().normalize().multiply(0.323), -90)));
                stand.teleport(stand.getLocation().add(stand.getLocation().getDirection().normalize().multiply(-0.115)));
                stand.teleport(stand.getLocation().add(0, 0.32, 0));
            }
            case ITEM -> {
            }
            default -> stand.setRenderMode(Item.RenderMode.ITEM);
        }

        switch (mode) {
            case ITEM -> stand.setRenderMode(Item.RenderMode.ITEM);
            case BLOCK -> {
                stand.setRenderMode(Item.RenderMode.BLOCK);
                stand.teleport(stand.getLocation().add(stand.getLocation().getDirection().normalize().multiply(0.14)));
                stand.teleport(stand.getLocation().add(rotateVectorAroundY(stand.getLocation().getDirection().normalize().multiply(0.102), -90)));
                stand.teleport(stand.getLocation().add(0.0, 0.084, 0.0));
                stand.setRotation(stand.getLocation().getYaw() + 45, stand.getLocation().getPitch());
            }
            case LOW_BLOCK -> {
                stand.setRenderMode(Item.RenderMode.LOW_BLOCK);
                stand.teleport(stand.getLocation().add(stand.getLocation().getDirection().normalize().multiply(0.15)));
                stand.teleport(stand.getLocation().add(rotateVectorAroundY(stand.getLocation().getDirection().normalize().multiply(0.09), -90)));
                stand.teleport(stand.getLocation().add(0.0, 0.02, 0.0));
                stand.setRotation(stand.getLocation().getYaw() + 45, stand.getLocation().getPitch());
            }
            case TOOL -> {
                stand.setRenderMode(Item.RenderMode.TOOL);
                stand.teleport(stand.getLocation().add(0, -0.26, 0));
                stand.teleport(stand.getLocation().add(stand.getLocation().getDirection().normalize().multiply(-0.1)));
                stand.teleport(stand.getLocation().add(rotateVectorAroundY(stand.getLocation().getDirection().normalize().multiply(-0.3), -90)));
            }
            case STANDING -> {
                stand.setRenderMode(Item.RenderMode.STANDING);
                stand.teleport(stand.getLocation().add(0, -0.32, 0));
                stand.teleport(stand.getLocation().add(stand.getLocation().getDirection().normalize().multiply(0.115)));
                stand.teleport(stand.getLocation().add(rotateVectorAroundY(stand.getLocation().getDirection().normalize().multiply(-0.323), -90)));
            }
            default -> throw new IllegalArgumentException("Unsupported crafting-table render mode: " + mode);
        }
    }

    public Map<String, Item> spawnDisplayEntitys(Player player, Block block) { //.add(0.68, 0.600781, 0.35)
        Map<String, Item> map = new HashMap<>();
        Location loc = block.getLocation().clone().add(0.5, 0.600781, 0.5);
        DisplayEntity center = new DisplayEntity(loc);
        float yaw = getCardinalDirection(player);
        center.setRotation(yaw, center.getLocation().getPitch());
        Vector vector = rotateVectorAroundY(center.getLocation().clone().getDirection().normalize().multiply(0.19), -100).add(center.getLocation().clone().getDirection().normalize().multiply(-0.11));
        Item slot5 = new Item(loc.clone().add(vector), Item.RenderMode.ITEM);
        setStand(slot5, yaw);
        Item slot2 = new Item(slot5.getLocation().clone().add(center.getLocation().clone().getDirection().normalize().multiply(0.2)), Item.RenderMode.ITEM);
        setStand(slot2, yaw);
        Item slot1 = new Item(slot2.getLocation().clone().add(rotateVectorAroundY(center.getLocation().clone().getDirection().normalize().multiply(0.2), -90)), Item.RenderMode.ITEM);
        setStand(slot1, yaw);
        Item slot3 = new Item(slot2.getLocation().clone().add(rotateVectorAroundY(center.getLocation().clone().getDirection().normalize().multiply(0.2), 90)), Item.RenderMode.ITEM);
        setStand(slot3, yaw);
        Item slot4 = new Item(slot5.getLocation().clone().add(rotateVectorAroundY(center.getLocation().clone().getDirection().normalize().multiply(0.2), -90)), Item.RenderMode.ITEM);
        setStand(slot4, yaw);
        Item slot6 = new Item(slot5.getLocation().clone().add(rotateVectorAroundY(center.getLocation().clone().getDirection().normalize().multiply(0.2), 90)), Item.RenderMode.ITEM);
        setStand(slot6, yaw);
        Item slot8 = new Item(slot5.getLocation().clone().add(center.getLocation().getDirection().clone().normalize().multiply(-0.2)), Item.RenderMode.ITEM);
        setStand(slot8, yaw);
        Item slot7 = new Item(slot8.getLocation().clone().add(rotateVectorAroundY(center.getLocation().clone().getDirection().normalize().multiply(0.2), -90)), Item.RenderMode.ITEM);
        setStand(slot7, yaw);
        Item slot9 = new Item(slot8.getLocation().clone().add(rotateVectorAroundY(center.getLocation().clone().getDirection().normalize().multiply(0.2), 90)), Item.RenderMode.ITEM);
        setStand(slot9, yaw);

        map.put("1", slot1);
        map.put("2", slot2);
        map.put("3", slot3);
        map.put("4", slot4);
        map.put("5", slot5);
        map.put("6", slot6);
        map.put("7", slot7);
        map.put("8", slot8);
        map.put("9", slot9);

        DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMSTAND, KEY), slot1);
        DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMSTAND, KEY), slot2);
        DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMSTAND, KEY), slot3);
        DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMSTAND, KEY), slot4);
        DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMSTAND, KEY), slot5);
        DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMSTAND, KEY), slot6);
        DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMSTAND, KEY), slot7);
        DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMSTAND, KEY), slot8);
        DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMSTAND, KEY), slot9);

        return map;
    }

    public void setStand(Item stand, float yaw) {
        stand.setRotation(yaw, stand.getLocation().getPitch());
    }

    public Vector rotateVectorAroundY(Vector vector, double degrees) {
        double rad = Math.toRadians(degrees);

        double currentX = vector.getX();
        double currentZ = vector.getZ();

        double cosine = Math.cos(rad);
        double sine = Math.sin(rad);

        return new Vector((cosine * currentX - sine * currentZ), vector.getY(), (sine * currentX + cosine * currentZ));
    }

    public float getCardinalDirection(Entity e) {

        double rotation = (e.getLocation().getYaw() - 90.0F) % 360.0F;

        if (rotation < 0.0D) {
            rotation += 360.0D;
        }
        if ((0.0D <= rotation) && (rotation < 45.0D)) {
            return 90.0F;
        }
        if ((45.0D <= rotation) && (rotation < 135.0D)) {
            return 180.0F;
        }
        if ((135.0D <= rotation) && (rotation < 225.0D)) {
            return -90.0F;
        }
        if ((225.0D <= rotation) && (rotation < 315.0D)) {
            return 0.0F;
        }
        if ((315.0D <= rotation) && (rotation < 360.0D)) {
            return 90.0F;
        }
        return 0.0F;
    }

}
