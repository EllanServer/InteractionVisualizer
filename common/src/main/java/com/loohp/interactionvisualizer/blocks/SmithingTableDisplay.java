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
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
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
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;

public class SmithingTableDisplay extends VisualizerInteractDisplay implements Listener {

    public static final EntryKey KEY = new EntryKey("smithing_table");

    public Map<Block, Map<String, Object>> openedSTables = new HashMap<>();
    public Map<Player, Block> playermap = new HashMap<>();

    @Override
    public EntryKey key() {
        return KEY;
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
            if (!(player.getOpenInventory().getTopInventory() instanceof SmithingInventory)) {
                return;
            }

            Block block = player.getTargetBlockExact(7, FluidCollisionMode.NEVER);
            if (block == null || !block.getType().equals(Material.SMITHING_TABLE)) {
                return;
            }

            playermap.put(player, block);
        }

        InventoryView view = player.getOpenInventory();
        Block block = playermap.get(player);
        Location loc = block.getLocation();

        int maxSlot = view.getTopInventory().getSize() - 1;
        String maxSlotStr = String.valueOf(maxSlot);

        if (!openedSTables.containsKey(block)) {
            Map<String, Object> map = new HashMap<>();
            map.put("Player", player);
            map.put(maxSlotStr, "N/A");
            map.putAll(spawnDisplayEntitys(player, block, maxSlot));
            openedSTables.put(block, map);
        }

        Map<String, Object> map = openedSTables.get(block);

        if (!map.get("Player").equals(player)) {
            return;
        }
        ItemStack[] items = new ItemStack[maxSlot + 1];
        for (int i = 0; i <= maxSlot; i++) {
            items[i] = view.getItem(i);
        }

        ItemStack itemstack = view.getItem(maxSlot);
        if (itemstack != null && itemstack.getType().equals(Material.AIR)) {
            itemstack = null;
        }
        if (map.get(maxSlotStr) instanceof String) {
            if (itemstack != null) {
                Item item = new Item(loc.clone().add(0.5, 1.2, 0.5));
                item.setItemStack(itemstack);
                item.setVelocity(new Vector(0, 0, 0));
                item.setPickupDelay(32767);
                item.setGravity(false);
                map.put(maxSlotStr, item);
                DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY), item);
                DisplayManager.updateItem(item);
            } else {
                map.put(maxSlotStr, "N/A");
            }
        } else {
            Item item = (Item) map.get(maxSlotStr);
            if (itemstack != null) {
                if (!item.getItemStack().equals(itemstack)) {
                    item.setItemStack(itemstack);
                    DisplayManager.updateItem(item);
                }
            } else {
                map.put(maxSlotStr, "N/A");
                DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
            }
        }
        for (int i = 0; i < maxSlot; i++) {
            Item stand = (Item) map.get(String.valueOf(i));
            ItemStack item = items[i];
            if (item == null || item.getType().equals(Material.AIR)) {
                item = null;
            }
            if (item != null) {
                MaterialMode materialMode = MaterialUtils.getMaterialType(item);
                boolean changed = materialMode != standMode(stand);
                if (changed) {
                    toggleStandMode(stand, materialMode);
                }
                if (!item.equals(stand.getItemStack())) {
                    changed = true;
                    stand.setItemStack(item);
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

        Location loc1 = ((Item) map.get("0")).getLocation();
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
    public void onSmithingTable(InventoryClickEvent event) {
        if (event.isCancelled()) {
            return;
        }
        int maxSlot = event.getView().getTopInventory().getSize() - 1;
        String maxSlotStr = String.valueOf(maxSlot);
        if (event.getRawSlot() != maxSlot) {
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
            if (!InventoryUtils.stillHaveSpace(event.getWhoClicked().getInventory(), event.getView().getItem(maxSlot).getType())) {
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

        Player player = (Player) event.getWhoClicked();
        Block block = playermap.get(player);

        if (!openedSTables.containsKey(block)) {
            return;
        }

        Map<String, Object> map = openedSTables.get(block);
        if (!map.get("Player").equals(event.getWhoClicked())) {
            return;
        }

        ItemStack itemstack = event.getCurrentItem().clone();
        Location loc = block.getLocation();
        InventoryView view = event.getView();

        Item slot0;
        Item slot1;
        Item slot2;
        if (maxSlot == 2) {
            slot0 = null;
            slot1 = (Item) map.get("0");
            slot2 = (Item) map.get("1");
        } else {
            slot0 = (Item) map.get("0");
            slot1 = (Item) map.get("1");
            slot2 = (Item) map.get("2");
        }

        if (map.get(maxSlotStr) instanceof String) {
            Item result = new Item(block.getLocation().clone().add(0.5, 1.2, 0.5));
            result.setItemStack(itemstack);
            result.setVelocity(new Vector());
            result.setPickupDelay(32767);
            result.setGravity(false);
            map.put(maxSlotStr, result);
            DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY), result);
        }
        Item item = (Item) map.get(maxSlotStr);

        Inventory before = Bukkit.createInventory(null, 9);
        for (int i = 0; i <= maxSlot; i++) {
            before.setItem(i, InventoryUtils.cloneItem(view.getItem(i)));
        }

        Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory() != view) {
                return;
            }
            Inventory after = Bukkit.createInventory(null, 9);
            for (int i = 0; i <= maxSlot; i++) {
                after.setItem(i, InventoryUtils.cloneItem(view.getItem(i)));
            }

            if (InventoryUtils.compareContents(before, after)) {
                return;
            }

            openedSTables.remove(block);
            InteractionVisualizer.lightManager.deleteLight(((Item) map.get("0")).getLocation());

            float yaw = getCardinalDirection(player);
            Vector vector = new Location(slot1.getWorld(), slot1.getLocation().getX(), slot1.getLocation().getY(), slot1.getLocation().getZ(), yaw, 0).getDirection().normalize();
            slot1.teleport(slot1.getLocation().add(rotateVectorAroundY(vector.clone(), 90).multiply(0.1)));
            slot2.teleport(slot2.getLocation().add(rotateVectorAroundY(vector.clone(), -90).multiply(0.1)));

            if (slot0 != null) {
                DisplayManager.updateItem(slot0);
            }
            DisplayManager.updateItem(slot1);
            DisplayManager.updateItem(slot2);

            item.setItemStack(itemstack);
            if (slot0 != null) {
                slot0.setLocked(true);
            }
            slot1.setLocked(true);
            slot2.setLocked(true);
            item.setLocked(true);

            Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
                for (Player each : InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY)) {
                    each.spawnParticle(Particle.CLOUD, loc.clone().add(0.5, 1.1, 0.5), 10, 0.05, 0.05, 0.05, 0.05);
                }
            }, 6);

            Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
                DisplayManager.updateItem(item);
                DisplayManager.collectItem(item, player);

                Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
                    if (slot0 != null) {
                        DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), slot0);
                    }
                    DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), slot1);
                    DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), slot2);
                }, 8);
            }, 10);
        }, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUseSmithingTable(InventoryClickEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (GameMode.SPECTATOR.equals(event.getWhoClicked().getGameMode())) {
            return;
        }
        if (!playermap.containsKey((Player) event.getWhoClicked())) {
            return;
        }
        int maxSlot = event.getView().getTopInventory().getSize() - 1;
        if (event.getRawSlot() >= 0 && event.getRawSlot() <= maxSlot) {
            DisplayManager.sendHandMovement(InteractionVisualizerAPI.getPlayers(), (Player) event.getWhoClicked());
        }
    }

    @EventHandler
    public void onDragSmithingTable(InventoryDragEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (GameMode.SPECTATOR.equals(event.getWhoClicked().getGameMode())) {
            return;
        }
        if (event.getView().getTopInventory() == null) {
            return;
        }
        if (!playermap.containsKey((Player) event.getWhoClicked())) {
            return;
        }
        int maxSlot = event.getView().getTopInventory().getSize() - 1;
        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot <= maxSlot) {
                DisplayManager.sendHandMovement(InteractionVisualizerAPI.getPlayers(), (Player) event.getWhoClicked());
                break;
            }
        }
    }

    @EventHandler
    public void onCloseSmithingTable(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        Block block = playermap.remove(player);
        if (block == null) {
            return;
        }

        Map<String, Object> map = openedSTables.get(block);
        if (map == null || !map.get("Player").equals(player)) {
            return;
        }

        int maxSlot = event.getView().getTopInventory().getSize() - 1;

        for (int i = 0; i <= maxSlot; i++) {
            if (!(map.get(String.valueOf(i)) instanceof String)) {
                Object entity = map.get(String.valueOf(i));
                if (entity instanceof Item) {
                    DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), (Item) entity);
                } else if (entity instanceof DisplayEntity) {
                    DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), (DisplayEntity) entity);
                }
            }
        }

        if (map.get("0") instanceof Item entity) {
            InteractionVisualizer.lightManager.deleteLight(entity.getLocation());
        }
        openedSTables.remove(block);
    }

    public MaterialMode standMode(Item stand) {
        return switch (stand.getRenderMode()) {
            case ITEM -> MaterialMode.ITEM;
            case BLOCK -> MaterialMode.BLOCK;
            case LOW_BLOCK -> MaterialMode.LOWBLOCK;
            case TOOL -> MaterialMode.TOOL;
            case STANDING -> MaterialMode.STANDING;
            default -> null;
        };
    }

    public void toggleStandMode(Item stand, MaterialMode mode) {
        Item.RenderMode previous = stand.getRenderMode();
        if (previous != Item.RenderMode.ITEM) {
            if (previous == Item.RenderMode.BLOCK) {
                stand.setRenderMode(Item.RenderMode.ITEM);
                stand.setRotation(stand.getLocation().getYaw() - 45, stand.getLocation().getPitch());
                stand.teleport(stand.getLocation().add(0.0, -0.084, 0.0));
                stand.teleport(stand.getLocation().add(rotateVectorAroundY(stand.getLocation().clone().getDirection().normalize().multiply(-0.102), -90)));
                stand.teleport(stand.getLocation().add(stand.getLocation().clone().getDirection().normalize().multiply(-0.14)));

            }
            if (previous == Item.RenderMode.LOW_BLOCK) {
                stand.setRenderMode(Item.RenderMode.ITEM);
                stand.setRotation(stand.getLocation().getYaw() - 45, stand.getLocation().getPitch());
                stand.teleport(stand.getLocation().add(0.0, -0.02, 0.0));
                stand.teleport(stand.getLocation().add(rotateVectorAroundY(stand.getLocation().clone().getDirection().normalize().multiply(-0.09), -90)));
                stand.teleport(stand.getLocation().add(stand.getLocation().clone().getDirection().normalize().multiply(-0.15)));

            }
            if (previous == Item.RenderMode.TOOL) {
                stand.setRenderMode(Item.RenderMode.ITEM);
                stand.teleport(stand.getLocation().add(rotateVectorAroundY(stand.getLocation().clone().getDirection().normalize().multiply(0.3), -90)));
                stand.teleport(stand.getLocation().add(stand.getLocation().clone().getDirection().normalize().multiply(0.1)));
                stand.teleport(stand.getLocation().add(0, 0.26, 0));
            }
            if (previous == Item.RenderMode.STANDING) {
                stand.setRenderMode(Item.RenderMode.ITEM);
                stand.teleport(stand.getLocation().add(rotateVectorAroundY(stand.getLocation().getDirection().normalize().multiply(0.323), -90)));
                stand.teleport(stand.getLocation().add(stand.getLocation().getDirection().normalize().multiply(-0.115)));
                stand.teleport(stand.getLocation().add(0, 0.32, 0));
            }
        }
        if (mode == MaterialMode.BLOCK) {
            stand.setRenderMode(Item.RenderMode.BLOCK);
            stand.teleport(stand.getLocation().add(stand.getLocation().clone().getDirection().normalize().multiply(0.14)));
            stand.teleport(stand.getLocation().add(rotateVectorAroundY(stand.getLocation().clone().getDirection().normalize().multiply(0.102), -90)));
            stand.teleport(stand.getLocation().add(0.0, 0.084, 0.0));
            stand.setRotation(stand.getLocation().getYaw() + 45, stand.getLocation().getPitch());
        }
        if (mode == MaterialMode.LOWBLOCK) {
            stand.setRenderMode(Item.RenderMode.LOW_BLOCK);
            stand.teleport(stand.getLocation().add(stand.getLocation().clone().getDirection().normalize().multiply(0.15)));
            stand.teleport(stand.getLocation().add(rotateVectorAroundY(stand.getLocation().clone().getDirection().normalize().multiply(0.09), -90)));
            stand.teleport(stand.getLocation().add(0.0, 0.02, 0.0));
            stand.setRotation(stand.getLocation().getYaw() + 45, stand.getLocation().getPitch());
        }
        if (mode == MaterialMode.TOOL) {
            stand.setRenderMode(Item.RenderMode.TOOL);
            stand.teleport(stand.getLocation().add(0, -0.26, 0));
            stand.teleport(stand.getLocation().add(stand.getLocation().clone().getDirection().normalize().multiply(-0.1)));
            stand.teleport(stand.getLocation().add(rotateVectorAroundY(stand.getLocation().clone().getDirection().normalize().multiply(-0.3), -90)));
        }
        if (mode == MaterialMode.STANDING) {
            stand.setRenderMode(Item.RenderMode.STANDING);
            stand.teleport(stand.getLocation().add(0, -0.32, 0));
            stand.teleport(stand.getLocation().add(stand.getLocation().getDirection().normalize().multiply(0.115)));
            stand.teleport(stand.getLocation().add(rotateVectorAroundY(stand.getLocation().getDirection().normalize().multiply(-0.323), -90)));
        }
    }

    public Map<String, Item> spawnDisplayEntitys(Player player, Block block, int maxSlot) { //.add(0.68, 0.600781, 0.35)
        Map<String, Item> map = new HashMap<>();
        Location loc = block.getLocation().clone().add(0.5, 0.600781, 0.5);
        DisplayEntity center = new DisplayEntity(loc);
        float yaw = getCardinalDirection(player);
        center.setRotation(yaw, center.getLocation().getPitch());
        setStand(center);
        center.setCustomName("IV.SmithingTable.Center");
        Vector vector = rotateVectorAroundY(center.getLocation().clone().getDirection().normalize().multiply(0.19), -100).add(center.getLocation().clone().getDirection().normalize().multiply(-0.11));
        DisplayEntity middle = new DisplayEntity(loc.clone().add(vector));
        middle.setRotation(yaw, middle.getLocation().getPitch());

        Item slot0 = new Item(middle.getLocation(), Item.RenderMode.ITEM);
        setStand(slot0, yaw);
        Item slot1 = new Item(middle.getLocation().clone().add(rotateVectorAroundY(center.getLocation().clone().getDirection().normalize().multiply(0.3), -90)), Item.RenderMode.ITEM);
        setStand(slot1, yaw + 20);
        Item slot2 = new Item(middle.getLocation().clone().add(rotateVectorAroundY(center.getLocation().clone().getDirection().normalize().multiply(0.3), 90)), Item.RenderMode.ITEM);
        setStand(slot2, yaw - 20);

        if (maxSlot == 2) {
            map.put("0", slot1);
            map.put("1", slot2);
        } else {
            map.put("0", slot0);
            map.put("1", slot1);
            map.put("2", slot2);

            DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMSTAND, KEY), slot0);
        }
        DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMSTAND, KEY), slot1);
        DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMSTAND, KEY), slot2);

        return map;
    }

    public void setStand(Item stand, float yaw) {
        stand.setGravity(false);
        stand.setSilent(true);
        stand.setVelocity(new Vector());
        stand.setPickupDelay(32767);
        stand.setRotation(yaw, stand.getLocation().getPitch());
    }

    public void setStand(DisplayEntity stand) {
        stand.setArms(true);
        stand.setBasePlate(false);
        stand.setMarker(true);
        stand.setSmall(true);
        stand.setSilent(true);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setVisible(false);
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
