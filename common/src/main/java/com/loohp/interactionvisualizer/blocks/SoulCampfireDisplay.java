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
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.managers.PlayerLocationManager;
import com.loohp.interactionvisualizer.managers.TileEntityManager;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.objectholders.TileEntity.TileEntityType;
import com.loohp.interactionvisualizer.utils.ChatColorUtils;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SoulCampfireDisplay extends VisualizerRunnableDisplay implements Listener {

    public static final EntryKey KEY = new EntryKey("soul_campfire");

    public ConcurrentHashMap<Block, Map<String, Object>> soulcampfireMap = new ConcurrentHashMap<>();
    private int checkingPeriod = 20;
    private int gcPeriod = 600;
    private String progressBarCharacter = "";
    private String emptyColor = "&7";
    private String filledColor = "&e";
    private int progressBarLength = 10;

    public SoulCampfireDisplay() {
        onReload(new InteractionVisualizerReloadEvent());
    }

    @EventHandler
    public void onReload(InteractionVisualizerReloadEvent event) {
        checkingPeriod = InteractionVisualizer.plugin.getConfiguration().getInt("Blocks.SoulCampfire.CheckingPeriod");
        gcPeriod = InteractionVisualizerAPI.getGCPeriod();
        progressBarCharacter = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.SoulCampfire.Options.ProgressBarCharacter"));
        emptyColor = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.SoulCampfire.Options.EmptyColor"));
        filledColor = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.SoulCampfire.Options.FilledColor"));
        progressBarLength = InteractionVisualizer.plugin.getConfiguration().getInt("Blocks.SoulCampfire.Options.ProgressBarLength");
    }

    @Override
    public EntryKey key() {
        return KEY;
    }

    @Override
    public ScheduledTask gc() {
        return Scheduler.runTaskTimer(InteractionVisualizer.plugin, () -> {
            Iterator<Entry<Block, Map<String, Object>>> itr = soulcampfireMap.entrySet().iterator();
            int count = 0;
            int maxper = (int) Math.ceil((double) soulcampfireMap.size() / (double) gcPeriod);
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
                        if (map.get("1") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("1");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        if (map.get("2") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("2");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        if (map.get("3") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("3");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        if (map.get("4") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("4");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        soulcampfireMap.remove(block);
                        return;
                    }
                    if (!block.getType().equals(Material.SOUL_CAMPFIRE)) {
                        Map<String, Object> map = entry.getValue();
                        if (map.get("1") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("1");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        if (map.get("2") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("2");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        if (map.get("3") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("3");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        if (map.get("4") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("4");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        soulcampfireMap.remove(block);
                        return;
                    }
                }, delay, block.getLocation());
            }
        }, 0, gcPeriod);
    }

    @Override
    public ScheduledTask run() {
        return Scheduler.runTaskTimer(InteractionVisualizer.plugin, () -> {
            Set<Block> list = nearbySoulCampfire();
            for (Block block : list) {
                Scheduler.runTask(InteractionVisualizer.plugin, () -> {
                    if (soulcampfireMap.get(block) == null && isActive(block.getLocation())) {
                        if (block.getType().equals(Material.SOUL_CAMPFIRE)) {
                            HashMap<String, Object> map = new HashMap<>();
                            map.putAll(spawnDisplayEntitys(block));
                            soulcampfireMap.put(block, map);
                        }
                    }
                }, block.getLocation());
            }

            Iterator<Entry<Block, Map<String, Object>>> itr = soulcampfireMap.entrySet().iterator();
            int count = 0;
            int maxper = (int) Math.ceil((double) soulcampfireMap.size() / (double) checkingPeriod);
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
                    if (!block.getType().equals(Material.SOUL_CAMPFIRE)) {
                        return;
                    }
                    org.bukkit.block.Campfire soulcampfire = (org.bukkit.block.Campfire) block.getState();
                    boolean isLit = ((Campfire) block.getBlockData()).isLit();

                    {
                        ItemStack itemstack1 = soulcampfire.getItem(0);
                        if (itemstack1 != null) {
                            if (itemstack1.getType().equals(Material.AIR)) {
                                itemstack1 = null;
                            }
                        }
                        ItemStack itemstack2 = soulcampfire.getItem(1);
                        if (itemstack2 != null) {
                            if (itemstack2.getType().equals(Material.AIR)) {
                                itemstack2 = null;
                            }
                        }
                        ItemStack itemstack3 = soulcampfire.getItem(2);
                        if (itemstack3 != null) {
                            if (itemstack3.getType().equals(Material.AIR)) {
                                itemstack3 = null;
                            }
                        }
                        ItemStack itemstack4 = soulcampfire.getItem(3);
                        if (itemstack4 != null) {
                            if (itemstack4.getType().equals(Material.AIR)) {
                                itemstack4 = null;
                            }
                        }

                        DisplayEntity stand1 = (DisplayEntity) entry.getValue().get("1");
                        DisplayEntity stand2 = (DisplayEntity) entry.getValue().get("2");
                        DisplayEntity stand3 = (DisplayEntity) entry.getValue().get("3");
                        DisplayEntity stand4 = (DisplayEntity) entry.getValue().get("4");

                        if (isLit && itemstack1 != null) {
                            int time = soulcampfire.getCookTime(0);
                            int max = soulcampfire.getCookTimeTotal(0);
                            String symbol = "";
                            double percentagescaled = (double) time / (double) max * (double) progressBarLength;
                            double i = 1;
                            for (i = 1; i < percentagescaled; i++) {
                                symbol = symbol + filledColor + progressBarCharacter;
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

                            if (stand1.updateCustomName(symbol, true)) {
                                DisplayManager.updateDisplay(stand1);
                            }
                        } else {
                            if (stand1.updateCustomName("", false)) {
                                DisplayManager.updateDisplay(stand1);
                            }
                        }
                        if (isLit && itemstack2 != null) {
                            int time = soulcampfire.getCookTime(1);
                            int max = soulcampfire.getCookTimeTotal(1);
                            String symbol = "";
                            double percentagescaled = (double) time / (double) max * (double) progressBarLength;
                            double i = 1;
                            for (i = 1; i < percentagescaled; i++) {
                                symbol = symbol + filledColor + progressBarCharacter;
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

                            if (stand2.updateCustomName(symbol, true)) {
                                DisplayManager.updateDisplay(stand2);
                            }
                        } else {
                            if (stand2.updateCustomName("", false)) {
                                DisplayManager.updateDisplay(stand2);
                            }
                        }
                        if (isLit && itemstack3 != null) {
                            int time = soulcampfire.getCookTime(2);
                            int max = soulcampfire.getCookTimeTotal(2);
                            String symbol = "";
                            double percentagescaled = (double) time / (double) max * (double) progressBarLength;
                            double i = 1;
                            for (i = 1; i < percentagescaled; i++) {
                                symbol = symbol + filledColor + progressBarCharacter;
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

                            if (stand3.updateCustomName(symbol, true)) {
                                DisplayManager.updateDisplay(stand3);
                            }
                        } else {
                            if (stand3.updateCustomName("", false)) {
                                DisplayManager.updateDisplay(stand3);
                            }
                        }
                        if (isLit && itemstack4 != null) {
                            int time = soulcampfire.getCookTime(3);
                            int max = soulcampfire.getCookTimeTotal(3);
                            String symbol = "";
                            double percentagescaled = (double) time / (double) max * (double) progressBarLength;
                            double i = 1;
                            for (i = 1; i < percentagescaled; i++) {
                                symbol = symbol + filledColor + progressBarCharacter;
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

                            if (stand4.updateCustomName(symbol, true)) {
                                DisplayManager.updateDisplay(stand4);
                            }
                        } else {
                            if (stand4.updateCustomName("", false)) {
                                DisplayManager.updateDisplay(stand4);
                            }
                        }
                    }
                }, delay, block.getLocation());
            }
        }, 0, checkingPeriod);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakSoulCampfire(TileEntityRemovedEvent event) {
        removeSoulCampfire(event.getBlock());
    }

    /** Retained for callers compiled against the former block-break handler. */
    public void onBreakSoulCampfire(BlockBreakEvent event) {
        removeSoulCampfire(event.getBlock());
    }

    private void removeSoulCampfire(Block block) {
        if (!soulcampfireMap.containsKey(block)) {
            return;
        }

        Map<String, Object> map = soulcampfireMap.get(block);
        if (map.get("1") instanceof DisplayEntity) {
            DisplayEntity stand = (DisplayEntity) map.get("1");
            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
        }
        if (map.get("2") instanceof DisplayEntity) {
            DisplayEntity stand = (DisplayEntity) map.get("2");
            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
        }
        if (map.get("3") instanceof DisplayEntity) {
            DisplayEntity stand = (DisplayEntity) map.get("3");
            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
        }
        if (map.get("4") instanceof DisplayEntity) {
            DisplayEntity stand = (DisplayEntity) map.get("4");
            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
        }
        soulcampfireMap.remove(block);
    }

    public Set<Block> nearbySoulCampfire() {
        return TileEntityManager.getTileEntities(TileEntityType.SOUL_CAMPFIRE);
    }

    public boolean isActive(Location loc) {
        return PlayerLocationManager.hasPlayerNearby(loc);
    }

    public Map<String, DisplayEntity> spawnDisplayEntitys(Block block) {
        Map<String, DisplayEntity> map = new HashMap<>();

        Location origin = block.getLocation();
        BlockData blockData = block.getState().getBlockData();
        BlockFace facing = ((Directional) blockData).getFacing();
        Location target = block.getRelative(facing).getLocation();
        Vector direction = rotateVectorAroundY(target.toVector().subtract(origin.toVector()).multiply(0.44194173), 135);

        Location loc = CampfireDisplay.labelOrigin(origin);
        DisplayEntity slot1 = new DisplayEntity(loc.clone().add(direction));
        setStand(slot1);
        DisplayEntity slot2 = new DisplayEntity(loc.clone().add(rotateVectorAroundY(direction.clone(), 90)));
        setStand(slot2);
        DisplayEntity slot3 = new DisplayEntity(loc.clone().add(rotateVectorAroundY(direction.clone(), 180)));
        setStand(slot3);
        DisplayEntity slot4 = new DisplayEntity(loc.clone().add(rotateVectorAroundY(direction.clone(), -90)));
        setStand(slot4);

        map.put("1", slot1);
        map.put("2", slot2);
        map.put("3", slot3);
        map.put("4", slot4);

        DisplayManager.spawnDisplay(InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY), slot1);
        DisplayManager.spawnDisplay(InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY), slot2);
        DisplayManager.spawnDisplay(InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY), slot3);
        DisplayManager.spawnDisplay(InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY), slot4);

        return map;
    }

    public void setStand(DisplayEntity stand) {
        CampfireDisplay.configureLabel(stand);
    }

    public Vector rotateVectorAroundY(Vector vector, double degrees) {
        double rad = Math.toRadians(degrees);

        double currentX = vector.getX();
        double currentZ = vector.getZ();

        double cosine = Math.cos(rad);
        double sine = Math.sin(rad);

        return new Vector((cosine * currentX - sine * currentZ), vector.getY(), (sine * currentX + cosine * currentZ));
    }

}
