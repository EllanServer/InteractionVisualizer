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
import com.loohp.interactionvisualizer.api.VisualizerRunnableDisplay;
import com.loohp.interactionvisualizer.api.events.InteractionVisualizerReloadEvent;
import com.loohp.interactionvisualizer.api.events.TileEntityRemovedEvent;
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.managers.PlayerLocationManager;
import com.loohp.interactionvisualizer.managers.TileEntityManager;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.objectholders.TileEntity.TileEntityType;
import com.loohp.interactionvisualizer.utils.MaterialUtils;
import com.loohp.interactionvisualizer.utils.WorkstationDisplayPositioning;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Crafter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CrafterDisplay extends VisualizerRunnableDisplay implements Listener {

    public static final EntryKey KEY = new EntryKey("crafter");

    public ConcurrentHashMap<Block, Map<String, Object>> crafterMap = new ConcurrentHashMap<>();
    private int checkingPeriod = 20;
    private int gcPeriod = 600;

    public CrafterDisplay() {
        onReload(new InteractionVisualizerReloadEvent());
    }

    @EventHandler
    public void onReload(InteractionVisualizerReloadEvent event) {
        checkingPeriod = InteractionVisualizer.plugin.getConfiguration().getInt("Blocks.Crafter.CheckingPeriod");
        gcPeriod = InteractionVisualizerAPI.getGCPeriod();
    }

    @Override
    public EntryKey key() {
        return KEY;
    }

    @Override
    public ScheduledTask gc() {
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            return null;
        }
        return Scheduler.runTaskTimer(InteractionVisualizer.plugin, () -> {
            Iterator<Entry<Block, Map<String, Object>>> itr = crafterMap.entrySet().iterator();
            int count = 0;
            int maxper = (int) Math.ceil((double) crafterMap.size() / (double) gcPeriod);
            int delay = 1;
            while (itr.hasNext()) {
                count++;
                if (count > maxper) {
                    count = 0;
                    delay++;
                }
                Entry<Block, Map<String, Object>> entry = itr.next();
                Block block = entry.getKey();
                Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
                    if (!isActive(block.getLocation())) {
                        Map<String, Object> map = entry.getValue();
                        for (int i = 1; i <= 9; i++) {
                            if (map.get(String.valueOf(i)) instanceof Item) {
                                Item stand = (Item) map.get(String.valueOf(i));
                                DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), stand);
                            }
                        }
                        crafterMap.remove(block);
                        return;
                    }
                    if (!block.getType().equals(Material.CRAFTER) || getCardinalDirection(block) < 0F) {
                        Map<String, Object> map = entry.getValue();
                        for (int i = 1; i <= 9; i++) {
                            if (map.get(String.valueOf(i)) instanceof Item) {
                                Item stand = (Item) map.get(String.valueOf(i));
                                DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), stand);
                            }
                        }
                        crafterMap.remove(block);
                        return;
                    }
                }, delay, block.getLocation());
            }
        }, 0, gcPeriod);
    }

    @Override
    public ScheduledTask run() {
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            BlockUpdateCoordinator.register(this, Set.of(Material.CRAFTER), this::nearbyCrafter,
                    checkingPeriod, gcPeriod, this::updateHybridBlock, this::removeTrackedDisplay);
            return null;
        }
        return legacyRun();
    }

    private ScheduledTask legacyRun() {
        return Scheduler.runTaskTimer(InteractionVisualizer.plugin, () -> {
            Set<Block> list = nearbyCrafter();
            for (Block block : list) {
                Scheduler.runTask(InteractionVisualizer.plugin, () -> {
                    if (crafterMap.get(block) == null && isActive(block.getLocation())) {
                        if (block.getType().equals(Material.CRAFTER) && getCardinalDirection(block) >= 0F) {
                            Map<String, Object> map = new HashMap<>(spawnDisplayEntitys(block));
                            crafterMap.put(block, map);
                        }
                    }
                }, block.getLocation());
            }

            Iterator<Entry<Block, Map<String, Object>>> itr = crafterMap.entrySet().iterator();
            int count = 0;
            int maxper = (int) Math.ceil((double) crafterMap.size() / (double) checkingPeriod);
            int delay = 1;
            while (itr.hasNext()) {
                Entry<Block, Map<String, Object>> entry = itr.next();

                count++;
                if (count > maxper) {
                    count = 0;
                    delay++;
                }
                Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> handleUpdate(entry.getKey(), entry.getValue()), delay, entry.getKey().getLocation());
            }
        }, 0, checkingPeriod);
    }

    private boolean updateHybridBlock(Block block) {
        if (!nearbyCrafter().contains(block)
                || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                || block.getType() != Material.CRAFTER || getCardinalDirection(block) < 0.0F) {
            removeTrackedDisplay(block);
            return false;
        }
        if (!isActive(block.getLocation())) {
            return false;
        }
        Map<String, Object> values = crafterMap.get(block);
        if (values == null) {
            values = new HashMap<>(spawnDisplayEntitys(block));
            crafterMap.put(block, values);
        }
        handleUpdate(block, values);
        return false;
    }

    public void handleUpdate(Block block, Map<String, Object> map) {
        if (block.getType() != Material.CRAFTER || crafterMap.get(block) != map
                || !(block.getState(false) instanceof Crafter crafter)) {
            return;
        }
        Inventory inventory = crafter.getInventory();

        ItemStack[] items = new ItemStack[] {
                inventory.getItem(0),
                inventory.getItem(1),
                inventory.getItem(2),
                inventory.getItem(3),
                inventory.getItem(4),
                inventory.getItem(5),
                inventory.getItem(6),
                inventory.getItem(7),
                inventory.getItem(8)
        };

        for (int i = 0; i < 9; i++) {
            Item stand = (Item) map.get(String.valueOf(i + 1));
            ItemStack itemStack = items[i];
            if (crafter.isSlotDisabled(i)) {
                itemStack = new ItemStack(Material.BARRIER);
            } else if (itemStack == null || itemStack.getType().equals(Material.AIR)) {
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
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCrafterDropItem(BlockDispenseEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Block block = event.getBlock();
        if (block.getType().equals(Material.CRAFTER)) {
            if (InteractionVisualizer.eventDrivenBlockUpdates) {
                BlockUpdateCoordinator.markDirty(block);
            } else {
                Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
                    Map<String, Object> map = crafterMap.get(block);
                    if (map != null) {
                        handleUpdate(block, map);
                    }
                }, 1, block.getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCrafterMoveItems(InventoryMoveItemEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Location initiatorLocation = event.getInitiator().getLocation();
        if (initiatorLocation != null) {
            Block block = initiatorLocation.getBlock();
            if (block.getType().equals(Material.CRAFTER)) {
                markCrafterChanged(block);
            }
        }
        Location destinationLocation = event.getDestination().getLocation();
        if (destinationLocation != null) {
            Block block = destinationLocation.getBlock();
            if (block.getType().equals(Material.CRAFTER)) {
                markCrafterChanged(block);
            }
        }
    }

    private void markCrafterChanged(Block block) {
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            BlockUpdateCoordinator.markDirty(block);
        } else {
            Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
                Map<String, Object> map = crafterMap.get(block);
                if (map != null) {
                    handleUpdate(block, map);
                }
            }, 1, block.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUseCrafter(InventoryClickEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (GameMode.SPECTATOR.equals(event.getWhoClicked().getGameMode())) {
            return;
        }
        if (event.getView().getTopInventory() == null) {
            return;
        }
        try {
            if (event.getView().getTopInventory().getLocation() == null) {
                return;
            }
        } catch (Exception | AbstractMethodError e) {
            return;
        }
        Block block = event.getView().getTopInventory().getLocation().getBlock();
        if (block == null) {
            return;
        }
        if (!block.getType().equals(Material.CRAFTER)) {
            return;
        }

        if (event.getRawSlot() >= 0 && event.getRawSlot() <= 8) {
            BlockUpdateCoordinator.markDirty(block);
            DisplayManager.sendHandMovement(InteractionVisualizerAPI.getPlayers(), (Player) event.getWhoClicked());
        }
        if (!InteractionVisualizer.eventDrivenBlockUpdates) {
            Map<String, Object> map = crafterMap.get(block);
            if (map != null) {
                handleUpdate(block, map);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakCrafter(TileEntityRemovedEvent event) {
        Block block = event.getBlock();
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            BlockUpdateCoordinator.remove(this, block);
        } else {
            removeTrackedDisplay(block);
        }
    }

    private void removeTrackedDisplay(Block block) {
        Map<String, Object> map = crafterMap.remove(block);
        if (map == null) {
            return;
        }
        for (int i = 1; i <= 9; i++) {
            if (map.get(String.valueOf(i)) instanceof Item) {
                Item stand = (Item) map.get(String.valueOf(i));
                DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), stand);
            }
        }
    }

    public Set<Block> nearbyCrafter() {
        return TileEntityManager.getTileEntities(TileEntityType.CRAFTER);
    }

    public boolean isActive(Location loc) {
        return PlayerLocationManager.hasPlayerNearby(loc);
    }

    public Item.RenderMode standMode(Item stand) {
        return stand.getRenderMode();
    }

    private static Item.RenderMode renderMode(MaterialUtils.MaterialMode mode) {
        return switch (mode) {
            case ITEM -> Item.RenderMode.ITEM;
            case BLOCK -> Item.RenderMode.BLOCK;
            case LOWBLOCK -> Item.RenderMode.LOW_BLOCK;
            case TOOL -> Item.RenderMode.TOOL;
            case STANDING -> Item.RenderMode.STANDING;
        };
    }

    public void toggleStandMode(Item stand, Item.RenderMode mode) {
        WorkstationDisplayPositioning.setRenderMode(stand, mode);
    }

    public Map<String, Item> spawnDisplayEntitys(Block block) {
        Map<String, Item> map = new HashMap<>();
        float yaw = getCardinalDirection(block);
        Location origin = block.getLocation();
        Item slot5 = WorkstationDisplayPositioning.gridItem(origin, yaw, 0.0, 0.0);
        setStand(slot5, yaw);
        Item slot2 = WorkstationDisplayPositioning.gridItem(origin, yaw, 0.0, 0.2);
        setStand(slot2, yaw);
        Item slot1 = WorkstationDisplayPositioning.gridItem(origin, yaw, -0.2, 0.2);
        setStand(slot1, yaw);
        Item slot3 = WorkstationDisplayPositioning.gridItem(origin, yaw, 0.2, 0.2);
        setStand(slot3, yaw);
        Item slot4 = WorkstationDisplayPositioning.gridItem(origin, yaw, -0.2, 0.0);
        setStand(slot4, yaw);
        Item slot6 = WorkstationDisplayPositioning.gridItem(origin, yaw, 0.2, 0.0);
        setStand(slot6, yaw);
        Item slot8 = WorkstationDisplayPositioning.gridItem(origin, yaw, 0.0, -0.2);
        setStand(slot8, yaw);
        Item slot7 = WorkstationDisplayPositioning.gridItem(origin, yaw, -0.2, -0.2);
        setStand(slot7, yaw);
        Item slot9 = WorkstationDisplayPositioning.gridItem(origin, yaw, 0.2, -0.2);
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

    /**
     * Retained for binary compatibility with integrations compiled against the
     * historical public helper.
     */
    public Vector rotateVectorAroundY(Vector vector, double degrees) {
        double radians = Math.toRadians(degrees);
        double currentX = vector.getX();
        double currentZ = vector.getZ();
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        return new Vector(cosine * currentX - sine * currentZ, vector.getY(),
                sine * currentX + cosine * currentZ);
    }

    public float getCardinalDirection(Block block) {
        org.bukkit.block.data.type.Crafter crafter = (org.bukkit.block.data.type.Crafter) block.getBlockData();
        String[] parts = crafter.getOrientation().name().split("_");
        BlockFace facing = BlockFace.valueOf(parts[0]);
        BlockFace grid = BlockFace.valueOf(parts[1]);
        if (grid.getModY() == 0) {
            return -1F;
        }

        double rotation = (Math.atan2(facing.getDirection().getZ(), facing.getDirection().getX()) + 90.0F) % 360.0F;

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
