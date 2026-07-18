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
import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.managers.PlayerLocationManager;
import com.loohp.interactionvisualizer.managers.TileEntityManager;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.objectholders.TileEntity.TileEntityType;
import com.loohp.interactionvisualizer.utils.ChatColorUtils;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BrewingStandDisplay extends VisualizerRunnableDisplay implements Listener {

    public static final EntryKey KEY = new EntryKey("brewing_stand");
    private final int max = 20 * 20;
    public ConcurrentHashMap<Block, Map<String, Object>> brewstand = new ConcurrentHashMap<>();
    private int checkingPeriod = 20;
    private int gcPeriod = 600;
    private String progressBarCharacter = "";
    private String emptyColor = "&7";
    private String filledColor = "&e";
    private String noFuelColor = "&c";
    private int progressBarLength = 10;

    public BrewingStandDisplay() {
        onReload(new InteractionVisualizerReloadEvent());
    }

    @EventHandler
    public void onReload(InteractionVisualizerReloadEvent event) {
        checkingPeriod = InteractionVisualizer.plugin.getConfiguration().getInt("Blocks.BrewingStand.CheckingPeriod");
        gcPeriod = InteractionVisualizerAPI.getGCPeriod();
        progressBarCharacter = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.BrewingStand.Options.ProgressBarCharacter"));
        emptyColor = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.BrewingStand.Options.EmptyColor"));
        filledColor = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.BrewingStand.Options.FilledColor"));
        noFuelColor = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.BrewingStand.Options.NoFuelColor"));
        progressBarLength = InteractionVisualizer.plugin.getConfiguration().getInt("Blocks.BrewingStand.Options.ProgressBarLength");
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
            Iterator<Entry<Block, Map<String, Object>>> itr = brewstand.entrySet().iterator();
            int count = 0;
            int maxper = (int) Math.ceil((double) brewstand.size() / (double) gcPeriod);
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
                        if (map.get("Item") instanceof Item) {
                            Item item = (Item) map.get("Item");
                            DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
                        }
                        if (map.get("Stand") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("Stand");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        brewstand.remove(block);
                        return;
                    }
                    if (!block.getType().equals(Material.BREWING_STAND)) {
                        Map<String, Object> map = entry.getValue();
                        if (map.get("Item") instanceof Item) {
                            Item item = (Item) map.get("Item");
                            DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
                        }
                        if (map.get("Stand") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("Stand");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        brewstand.remove(block);
                        return;
                    }
                }, delay, block.getLocation());
            }
        }, 0, gcPeriod);
    }

    @Override
    public ScheduledTask run() {
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            BlockUpdateCoordinator.register(this, Set.of(Material.BREWING_STAND),
                    this::nearbyBrewingStand, checkingPeriod, gcPeriod,
                    this::updateHybridBlock, this::removeTrackedDisplay);
            return null;
        }
        return legacyRun();
    }

    private ScheduledTask legacyRun() {
        return Scheduler.runTaskTimer(InteractionVisualizer.plugin, () -> {
            Set<Block> list = nearbyBrewingStand();
            for (Block block : list) {
                Scheduler.runTask(InteractionVisualizer.plugin, () -> {
                    if (brewstand.get(block) == null && isActive(block.getLocation())) {
                        if (block.getType().equals(Material.BREWING_STAND)) {
                            Map<String, Object> map = new HashMap<>();
                            map.put("Item", "N/A");
                            map.putAll(spawnDisplayEntitys(block));
                            brewstand.put(block, map);
                        }
                    }
                }, block.getLocation());
            }

            Iterator<Entry<Block, Map<String, Object>>> itr = brewstand.entrySet().iterator();
            int count = 0;
            int maxper = (int) Math.ceil((double) brewstand.size() / (double) checkingPeriod);
            int delay = 1;
            while (itr.hasNext()) {
                Entry<Block, Map<String, Object>> entry = itr.next();

                count++;
                if (count > maxper) {
                    count = 0;
                    delay++;
                }
                Block block = entry.getKey();
                Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
                    if (!isActive(block.getLocation())) {
                        return;
                    }
                    if (!block.getType().equals(Material.BREWING_STAND)) {
                        return;
                    }
                    org.bukkit.block.BrewingStand brewingstand =
                            (org.bukkit.block.BrewingStand) block.getState(false);

                    {
                        Inventory inv = brewingstand.getInventory();
                        ItemStack itemstack = inv.getItem(3);
                        if (itemstack != null) {
                            if (inv.getItem(3).getType().equals(Material.AIR)) {
                                itemstack = null;
                            }
                        }

                        Item item = null;
                        if (entry.getValue().get("Item") instanceof String) {
                            if (itemstack != null) {
                                item = new Item(brewingstand.getLocation().clone().add(0.5, 1.0, 0.5));
                                item.setItemStack(itemstack);
                                item.setVelocity(new Vector(0, 0, 0));
                                item.setPickupDelay(32767);
                                item.setGravity(false);
                                entry.getValue().put("Item", item);
                                DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY), item);
                                DisplayManager.updateItem(item);
                            } else {
                                entry.getValue().put("Item", "N/A");
                            }
                        } else {
                            item = (Item) entry.getValue().get("Item");
                            if (itemstack != null) {
                                if (!item.getItemStack().equals(itemstack)) {
                                    item.setItemStack(itemstack);
                                    DisplayManager.updateItem(item);
                                }
                                item.setPickupDelay(32767);
                                item.setGravity(false);
                            } else {
                                entry.getValue().put("Item", "N/A");
                                DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
                            }
                        }

                        if (brewingstand.getFuelLevel() == 0) {
                            DisplayEntity stand = (DisplayEntity) entry.getValue().get("Stand");
                            if (hasPotion(brewingstand)) {
                                String name = noFuelColor;
                                for (int i = 0; i < progressBarLength; i++) {
                                    name += progressBarCharacter;
                                }
                                if (stand.updateCustomName(name, true)) {
                                    DisplayManager.updateDisplay(stand);
                                }
                            } else {
                                if (stand.updateCustomName("", false)) {
                                    DisplayManager.updateDisplay(stand);
                                }
                            }
                        } else {
                            DisplayEntity stand = (DisplayEntity) entry.getValue().get("Stand");
                            if (hasPotion(brewingstand)) {
                                int time = brewingstand.getBrewingTime();
                                String symbol = "";
                                double percentagescaled = (double) (max - time) / (double) max * (double) progressBarLength;
                                double i = 1;
                                for (i = 1; i < percentagescaled; i++) {
                                    symbol += filledColor + progressBarCharacter;
                                }
                                i = i - 1;
                                if ((percentagescaled - i) > 0 && (percentagescaled - i) < 0.33) {
                                    symbol += emptyColor + progressBarCharacter;
                                } else if ((percentagescaled - i) > 0 && (percentagescaled - i) < 0.67) {
                                    symbol += emptyColor + progressBarCharacter;
                                } else if ((percentagescaled - i) > 0) {
                                    symbol += filledColor + progressBarCharacter;
                                }
                                for (i = progressBarLength - 1; i >= percentagescaled; i--) {
                                    symbol += emptyColor + progressBarCharacter;
                                }
                                if (stand.updateCustomName(symbol, true)) {
                                    DisplayManager.updateDisplay(stand);
                                }
                            } else {
                                if (stand.updateCustomName("", false)) {
                                    DisplayManager.updateDisplay(stand);
                                }
                            }
                        }
                    }
                }, delay, block.getLocation());
            }
        }, 0, checkingPeriod);
    }

    private boolean updateHybridBlock(Block block) {
        if (!nearbyBrewingStand().contains(block)
                || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                || block.getType() != Material.BREWING_STAND) {
            removeTrackedDisplay(block);
            return false;
        }
        if (!isActive(block.getLocation())) {
            return false;
        }
        Map<String, Object> values = brewstand.get(block);
        if (values == null) {
            values = new HashMap<>();
            values.put("Item", "N/A");
            values.putAll(spawnDisplayEntitys(block));
            brewstand.put(block, values);
        }
        org.bukkit.block.BrewingStand state = (org.bukkit.block.BrewingStand) block.getState(false);
        updateTrackedBlock(state, values);
        return state.getBrewingTime() > 0;
    }

    private void updateTrackedBlock(org.bukkit.block.BrewingStand state, Map<String, Object> values) {
        Inventory inventory = state.getInventory();
        ItemStack ingredient = inventory.getItem(3);
        if (ingredient != null && ingredient.getType() == Material.AIR) {
            ingredient = null;
        }
        if (values.get("Item") instanceof String) {
            if (ingredient != null) {
                Item item = new Item(state.getLocation().clone().add(0.5, 1.0, 0.5));
                item.setItemStack(ingredient);
                item.setVelocity(new Vector());
                item.setPickupDelay(32767);
                item.setGravity(false);
                values.put("Item", item);
                DisplayManager.sendItemSpawn(
                        InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY), item);
            }
        } else {
            Item item = (Item) values.get("Item");
            if (ingredient == null) {
                values.put("Item", "N/A");
                DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
            } else if (!item.getItemStack().equals(ingredient)) {
                item.setItemStack(ingredient);
                DisplayManager.updateItem(item);
            }
        }

        DisplayEntity stand = (DisplayEntity) values.get("Stand");
        if (!hasPotion(state)) {
            if (stand.updateCustomName("", false)) {
                DisplayManager.updateDisplay(stand);
            }
            return;
        }
        if (state.getFuelLevel() == 0) {
            if (stand.updateCustomName(noFuelColor + progressBarCharacter.repeat(
                    Math.max(0, progressBarLength)), true)) {
                DisplayManager.updateDisplay(stand);
            }
            return;
        }
        double scaled = (double) (max - state.getBrewingTime()) / (double) max
                * (double) progressBarLength;
        StringBuilder symbol = new StringBuilder(Math.max(16, progressBarLength * 3));
        double index = 1.0D;
        for (; index < scaled; index++) {
            symbol.append(filledColor).append(progressBarCharacter);
        }
        index--;
        if (scaled - index > 0.0D && scaled - index < 0.67D) {
            symbol.append(emptyColor).append(progressBarCharacter);
        } else if (scaled - index > 0.0D) {
            symbol.append(filledColor).append(progressBarCharacter);
        }
        for (index = progressBarLength - 1.0D; index >= scaled; index--) {
            symbol.append(emptyColor).append(progressBarCharacter);
        }
        if (stand.updateCustomName(symbol.toString(), true)) {
            DisplayManager.updateDisplay(stand);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUseBrewingStand(InventoryClickEvent event) {
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
        if (event.getView().getTopInventory().getLocation().getBlock() == null) {
            return;
        }
        if (!event.getView().getTopInventory().getLocation().getBlock().getType().equals(Material.BREWING_STAND)) {
            return;
        }

        if (event.getRawSlot() >= 0 && event.getRawSlot() <= 4) {
            BlockUpdateCoordinator.markDirty(event.getView().getTopInventory().getLocation().getBlock());
            DisplayManager.sendHandMovement(InteractionVisualizerAPI.getPlayers(), (Player) event.getWhoClicked());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragBrewingStand(InventoryDragEvent event) {
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
        if (event.getView().getTopInventory().getLocation().getBlock() == null) {
            return;
        }
        if (!event.getView().getTopInventory().getLocation().getBlock().getType().equals(Material.BREWING_STAND)) {
            return;
        }

        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot <= 4) {
                BlockUpdateCoordinator.markDirty(
                        event.getView().getTopInventory().getLocation().getBlock());
                DisplayManager.sendHandMovement(InteractionVisualizerAPI.getPlayers(), (Player) event.getWhoClicked());
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakBrewingStand(TileEntityRemovedEvent event) {
        Block block = event.getBlock();
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            BlockUpdateCoordinator.remove(this, block);
        } else {
            removeTrackedDisplay(block);
        }
    }

    private void removeTrackedDisplay(Block block) {
        Map<String, Object> map = brewstand.remove(block);
        if (map == null) {
            return;
        }
        if (map.get("Item") instanceof Item) {
            Item item = (Item) map.get("Item");
            DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
        }
        if (map.get("Stand") instanceof DisplayEntity) {
            DisplayEntity stand = (DisplayEntity) map.get("Stand");
            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
        }
    }

    public boolean hasPotion(org.bukkit.block.BrewingStand brewingstand) {
        Inventory inv = brewingstand.getInventory();
        if (inv.getItem(0) != null) {
            if (!inv.getItem(0).getType().equals(Material.AIR)) {
                return true;
            }
        }
        if (inv.getItem(1) != null) {
            if (!inv.getItem(1).getType().equals(Material.AIR)) {
                return true;
            }
        }
        if (inv.getItem(2) != null) {
            return !inv.getItem(2).getType().equals(Material.AIR);
        }
        return false;
    }

    public Set<Block> nearbyBrewingStand() {
        return TileEntityManager.getTileEntities(TileEntityType.BREWING_STAND);
    }

    public boolean isActive(Location loc) {
        return PlayerLocationManager.hasPlayerNearby(loc);
    }

    public Map<String, DisplayEntity> spawnDisplayEntitys(Block block) { //.add(0.68, 0.700781, 0.35)
        Map<String, DisplayEntity> map = new HashMap<>();
        Location loc = block.getLocation().clone().add(0.5, 0.700781, 0.5);
        DisplayEntity slot1 = new DisplayEntity(loc.clone());
        setStand(slot1);

        map.put("Stand", slot1);

        DisplayManager.spawnDisplay(InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY), slot1);

        return map;
    }

    public void setStand(DisplayEntity stand) {
        configureLabel(stand);
    }

    static void configureLabel(DisplayEntity stand) {
        stand.useLegacyNameTagStyle();
        stand.setBasePlate(false);
        stand.setMarker(true);
        stand.setGravity(false);
        stand.setSmall(true);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setVisible(false);
        stand.setCustomName("");
        stand.setRightArmPose(EulerAngle.ZERO);
    }

}
