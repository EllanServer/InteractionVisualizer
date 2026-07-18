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
import com.loohp.interactionvisualizer.objectholders.TileEntity;
import com.loohp.interactionvisualizer.objectholders.TileEntity.TileEntityType;
import com.loohp.interactionvisualizer.utils.ChatColorUtils;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rotatable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class BannerDisplay extends VisualizerRunnableDisplay implements Listener {

    public static final EntryKey KEY = new EntryKey("banner");

    public Map<Block, Map<String, Object>> bannerMap = new ConcurrentHashMap<>();
    private int checkingPeriod = 20;
    private int gcPeriod = 600;
    private boolean stripColorBlacklist;
    private Predicate<String> blacklist;

    public BannerDisplay() {
        onReload(new InteractionVisualizerReloadEvent());
    }

    @EventHandler
    public void onReload(InteractionVisualizerReloadEvent event) {
        checkingPeriod = InteractionVisualizer.plugin.getConfiguration().getInt("Blocks.Banner.CheckingPeriod");
        gcPeriod = InteractionVisualizerAPI.getGCPeriod();
        stripColorBlacklist = InteractionVisualizer.plugin.getConfiguration().getBoolean("Entities.Item.Options.Blacklist.StripColorWhenMatching");
        blacklist = InteractionVisualizer.plugin.getConfiguration().getStringList("Blocks.Banner.Options.Blacklist.List").stream().map(each -> {
            Pattern pattern = Pattern.compile(each);
            return (Predicate<String>) str -> pattern.matcher(str).matches();
        }).reduce(Predicate::or).orElse(s -> false);
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
            Iterator<Entry<Block, Map<String, Object>>> itr = bannerMap.entrySet().iterator();
            int count = 0;
            int maxper = (int) Math.ceil((double) bannerMap.size() / (double) gcPeriod);
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
                        bannerMap.remove(block);
                        return;
                    }
                    if (!isBanner(block.getType())) {
                        Map<String, Object> map = entry.getValue();
                        if (map.get("1") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("1");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        bannerMap.remove(block);
                    }
                }, delay, block.getLocation());
            }
        }, 0, gcPeriod);
    }

    @Override
    public ScheduledTask run() {
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            BlockUpdateCoordinator.register(this,
                    BlockUpdateCoordinator.materialsMatching(this::isBanner), this::nearbyBanner,
                    checkingPeriod, gcPeriod, this::updateHybridBlock, this::removeTrackedDisplay);
            return null;
        }
        return legacyRun();
    }

    private ScheduledTask legacyRun() {
        return Scheduler.runTaskTimer(InteractionVisualizer.plugin, () -> {
            Set<Block> list = nearbyBanner();
            for (Block block : list) {
                Scheduler.runTask(InteractionVisualizer.plugin, () -> {
                    if (bannerMap.get(block) == null && isActive(block.getLocation())) {
                        if (isBanner(block.getType())) {
                            Map<String, Object> map = new HashMap<>();
                            map.put("Item", "N/A");
                            map.putAll(spawnDisplayEntitys(block));
                            bannerMap.put(block, map);
                        }
                    }
                }, block.getLocation());
            }

            Iterator<Entry<Block, Map<String, Object>>> itr = bannerMap.entrySet().iterator();
            int count = 0;
            int maxper = (int) Math.ceil((double) bannerMap.size() / (double) checkingPeriod);
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
                    if (!isBanner(block.getType())) {
                        return;
                    }
                    Component name = ((Banner) block.getState(false)).customName();
                    {
                        DisplayEntity line1 = (DisplayEntity) entry.getValue().get("1");
                        if (name == null || PlainTextComponentSerializer.plainText().serialize(name).isEmpty()) {
                            if (!PlainTextComponentSerializer.plainText().serialize(line1.getCustomName()).equals("") || line1.isCustomNameVisible()) {
                                line1.setCustomName("");
                                line1.setCustomNameVisible(false);
                                DisplayManager.updateDisplay(line1);
                            }
                        } else {
                            Component component = name;
                            String matchingName = LegacyComponentSerializer.legacySection().serialize(component);
                            if (stripColorBlacklist) {
                                matchingName = ChatColorUtils.stripColor(matchingName);
                            }
                            if (blacklist.test(matchingName)) {
                                if (!PlainTextComponentSerializer.plainText().serialize(line1.getCustomName()).equals("") || line1.isCustomNameVisible()) {
                                    line1.setCustomName("");
                                    line1.setCustomNameVisible(false);
                                    DisplayManager.updateDisplay(line1);
                                }
                            } else {
                                if (!line1.getCustomName().equals(component) || !line1.isCustomNameVisible()) {
                                    line1.setCustomName(component);
                                    line1.setCustomNameVisible(true);
                                    DisplayManager.updateDisplay(line1);
                                }
                            }
                        }
                    }
                }, delay, block.getLocation());
            }
        }, 0, checkingPeriod);
    }

    private boolean updateHybridBlock(Block block) {
        if (!nearbyBanner().contains(block)
                || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                || !isBanner(block.getType())) {
            removeTrackedDisplay(block);
            return false;
        }
        if (!isActive(block.getLocation())) {
            return false;
        }
        Map<String, Object> values = bannerMap.get(block);
        if (values == null) {
            values = new HashMap<>();
            values.put("Item", "N/A");
            values.putAll(spawnDisplayEntitys(block));
            bannerMap.put(block, values);
        }
        updateTrackedBlock(block, values);
        return false;
    }

    private void updateTrackedBlock(Block block, Map<String, Object> values) {
        Component name = ((Banner) block.getState(false)).customName();
        DisplayEntity line = (DisplayEntity) values.get("1");
        if (name == null || PlainTextComponentSerializer.plainText().serialize(name).isEmpty()) {
            if (!PlainTextComponentSerializer.plainText().serialize(line.getCustomName()).isEmpty()
                    || line.isCustomNameVisible()) {
                line.setCustomName("");
                line.setCustomNameVisible(false);
                DisplayManager.updateDisplay(line);
            }
            return;
        }
        String matchingName = LegacyComponentSerializer.legacySection().serialize(name);
        if (stripColorBlacklist) {
            matchingName = ChatColorUtils.stripColor(matchingName);
        }
        if (blacklist.test(matchingName)) {
            if (!PlainTextComponentSerializer.plainText().serialize(line.getCustomName()).isEmpty()
                    || line.isCustomNameVisible()) {
                line.setCustomName("");
                line.setCustomNameVisible(false);
                DisplayManager.updateDisplay(line);
            }
        } else if (!line.getCustomName().equals(name) || !line.isCustomNameVisible()) {
            line.setCustomName(name);
            line.setCustomNameVisible(true);
            DisplayManager.updateDisplay(line);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakBanner(TileEntityRemovedEvent event) {
        Block block = event.getBlock();
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            BlockUpdateCoordinator.remove(this, block);
        } else {
            removeTrackedDisplay(block);
        }
    }

    private void removeTrackedDisplay(Block block) {
        Map<String, Object> map = bannerMap.remove(block);
        if (map == null) {
            return;
        }
        if (map.get("1") instanceof DisplayEntity) {
            DisplayEntity stand = (DisplayEntity) map.get("1");
            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
        }
    }

    public Set<Block> nearbyBanner() {
        return TileEntityManager.getTileEntities(TileEntityType.BANNER);
    }

    public boolean isActive(Location loc) {
        return PlayerLocationManager.hasPlayerNearby(loc);
    }

    public boolean isBanner(Material material) {
        TileEntityType type = TileEntity.getTileEntityType(material);
        return type != null && type.equals(TileEntityType.BANNER);
    }

    public boolean isWallBanner(Material material) {
        return material.name().contains("WALL");
    }

    @SuppressWarnings("deprecation")
    public Map<String, DisplayEntity> spawnDisplayEntitys(Block block) {
        Map<String, DisplayEntity> map = new HashMap<>();

        if (isWallBanner(block.getType())) {
            DisplayEntity line1 = new DisplayEntity(block.getLocation().add(0.5, 0.0, 0.5));
            setStand(line1);

            map.put("1", line1);

            DisplayManager.spawnDisplay(InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY), line1);
        } else {
            Location origin = block.getLocation().add(0.5, 1.0, 0.5);
            Rotatable rotate = (Rotatable) block.getBlockData();
            Vector vector = getDirection(rotate.getRotation()).multiply(0.3125);

            DisplayEntity line1 = new DisplayEntity(origin.clone().add(vector));
            setStand(line1);

            map.put("1", line1);

            DisplayManager.spawnDisplay(InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY), line1);
        }

        return map;
    }

    public Vector getDirection(BlockFace face) {
        Vector direction = new Vector(face.getModX(), face.getModY(), face.getModZ());
        if (face.getModX() != 0 || face.getModY() != 0 || face.getModZ() != 0) {
            direction.normalize();
        }
        return direction;
    }

    public void setStand(DisplayEntity stand) {
        stand.useLegacyNameTagStyle();
        stand.setBasePlate(false);
        stand.setMarker(true);
        stand.setGravity(false);
        stand.setSmall(true);
        stand.setSilent(true);
        stand.setInvulnerable(true);
        stand.setVisible(false);
        stand.setCustomName("");
        stand.setRightArmPose(EulerAngle.ZERO);
    }

}
