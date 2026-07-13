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
import com.loohp.interactionvisualizer.api.events.TileEntityActivatedEvent;
import com.loohp.interactionvisualizer.api.events.TileEntityAddedEvent;
import com.loohp.interactionvisualizer.api.events.TileEntityDeactivatedEvent;
import com.loohp.interactionvisualizer.api.events.TileEntityRemovedEvent;
import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.managers.PlayerLocationManager;
import com.loohp.interactionvisualizer.managers.PerformanceMetrics;
import com.loohp.interactionvisualizer.managers.TileEntityManager;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.objectholders.TileEntity.TileEntityType;
import com.loohp.interactionvisualizer.utils.ChatColorUtils;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Bee;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityEnterBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BeeNestDisplay extends VisualizerRunnableDisplay implements Listener {

    public static final EntryKey KEY = new EntryKey("bee_nest");

    public ConcurrentHashMap<Block, Map<String, Object>> beenestMap = new ConcurrentHashMap<>();
    private int checkingPeriod = 20;
    private int gcPeriod = 600;
    private String honeyLevelCharacter = "";
    private String emptyColor = "&7";
    private String filledColor = "&e";
    private String noCampfireColor = "&c";
    private String beeCountText = "&e{Current}&6/{Max}";
    private final BlockUpdateScheduler<Block> blockUpdates;

    public BeeNestDisplay() {
        onReload(new InteractionVisualizerReloadEvent());
        this.blockUpdates = new BlockUpdateScheduler<>(this::nearbyBeenest,
                this.checkingPeriod, this.gcPeriod);
    }

    @EventHandler
    public void onReload(InteractionVisualizerReloadEvent event) {
        checkingPeriod = InteractionVisualizer.plugin.getConfiguration().getInt("Blocks.BeeNest.CheckingPeriod");
        gcPeriod = InteractionVisualizerAPI.getGCPeriod();
        honeyLevelCharacter = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.BeeNest.Options.HoneyLevelCharacter"));
        emptyColor = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.BeeNest.Options.EmptyColor"));
        filledColor = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.BeeNest.Options.FilledColor"));
        noCampfireColor = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.BeeNest.Options.NoCampfireColor"));
        beeCountText = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.BeeNest.Options.BeeCountText"));
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
            Iterator<Entry<Block, Map<String, Object>>> itr = beenestMap.entrySet().iterator();
            int count = 0;
            int maxper = (int) Math.ceil((double) beenestMap.size() / (double) gcPeriod);
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
                        if (map.get("0") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("0");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        if (map.get("1") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("1");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        beenestMap.remove(block);
                        return;
                    }
                    if (!block.getType().equals(Material.BEE_NEST)) {
                        Map<String, Object> map = entry.getValue();
                        if (map.get("0") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("0");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        if (map.get("1") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("1");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        beenestMap.remove(block);
                        return;
                    }
                }, delay, block.getLocation());
            }
        }, 0, gcPeriod);
    }

    @Override
    public ScheduledTask run() {
        if (!InteractionVisualizer.eventDrivenBlockUpdates) {
            return legacyRun();
        }
        return Scheduler.runTaskTimer(InteractionVisualizer.plugin, () -> {
            boolean collecting = PerformanceMetrics.isCollecting();
            long start = collecting ? System.nanoTime() : 0L;
            int checks = this.blockUpdates.tick(Bukkit.getCurrentTick(),
                    InteractionVisualizer.blockUpdateMaxDirtyPerTick, this::updateHybridBlock);
            if (collecting) {
                PerformanceMetrics.blockUpdateChecks(checks, System.nanoTime() - start);
            }
        }, 0, 1);
    }

    private ScheduledTask legacyRun() {
        return Scheduler.runTaskTimer(InteractionVisualizer.plugin, () -> {
            Set<Block> list = nearbyBeenest();
            for (Block block : list) {
                Scheduler.runTask(InteractionVisualizer.plugin, () -> {
                    if (beenestMap.get(block) == null && isActive(block.getLocation())) {
                        if (block.getType().equals(Material.BEE_NEST)) {
                            Map<String, Object> map = new HashMap<>();
                            map.putAll(spawnDisplayEntitys(block));
                            beenestMap.put(block, map);
                        }
                    }
                }, block.getLocation());
            }

            Iterator<Entry<Block, Map<String, Object>>> itr = beenestMap.entrySet().iterator();
            int count = 0;
            int maxper = (int) Math.ceil((double) beenestMap.size() / (double) checkingPeriod);
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
                    measureLegacyUpdate(block);
                }, delay, block.getLocation());
            }
        }, 0, checkingPeriod);
    }

    private void measureLegacyUpdate(Block block) {
        if (!PerformanceMetrics.isCollecting()) {
            updateBlock(block);
            return;
        }
        long start = System.nanoTime();
        try {
            updateBlock(block);
        } finally {
            PerformanceMetrics.blockUpdateChecks(1, System.nanoTime() - start);
        }
    }

    private boolean updateHybridBlock(Block block) {
        if (!TileEntityManager.getTileEntities(TileEntityType.BEE_NEST).contains(block)) {
            removeTrackedDisplay(block);
            return false;
        }
        if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            removeTrackedDisplay(block);
            return false;
        }
        if (block.getType() != Material.BEE_NEST) {
            removeTrackedDisplay(block);
            return false;
        }
        if (!isActive(block.getLocation())) {
            return false;
        }
        if (!beenestMap.containsKey(block)) {
            beenestMap.put(block, new HashMap<>(spawnDisplayEntitys(block)));
        }
        updateBlock(block);
        return false;
    }

    private void markDirty(Block block) {
        if (InteractionVisualizer.eventDrivenBlockUpdates && block != null
                && block.getType() == Material.BEE_NEST && beenestMap.containsKey(block)) {
            blockUpdates.markDirty(block, (long) Bukkit.getCurrentTick() + 1L);
        }
    }

    /** Optimized aggregate-listener entry after the affected vertical column has been scanned once. */
    public void onAffectedBeeBlock(Block block) {
        markDirty(block);
    }

    private void markAffectedColumnDirty(Block changedBlock) {
        if (!InteractionVisualizer.eventDrivenBlockUpdates || changedBlock == null) {
            return;
        }
        for (int distance = 1; distance <= 5; distance++) {
            markDirty(changedBlock.getRelative(BlockFace.UP, distance));
        }
    }

    public void onAffectedBlockPlace(BlockPlaceEvent event) {
        if (!InteractionVisualizer.eventDrivenBlockUpdates) {
            return;
        }
        markAffectedColumnDirty(event.getBlockPlaced());
    }

    public void onAffectedBlockBreak(BlockBreakEvent event) {
        if (!InteractionVisualizer.eventDrivenBlockUpdates) {
            return;
        }
        markAffectedColumnDirty(event.getBlock());
    }

    public void onAffectedBlockBurn(BlockBurnEvent event) {
        if (!InteractionVisualizer.eventDrivenBlockUpdates) {
            return;
        }
        markAffectedColumnDirty(event.getBlock());
    }

    public void onAffectedBlockFade(BlockFadeEvent event) {
        if (!InteractionVisualizer.eventDrivenBlockUpdates) {
            return;
        }
        markAffectedColumnDirty(event.getBlock());
    }

    public void onAffectedBlockIgnite(BlockIgniteEvent event) {
        if (!InteractionVisualizer.eventDrivenBlockUpdates) {
            return;
        }
        markAffectedColumnDirty(event.getBlock());
    }

    public void onAffectedFluidFlow(BlockFromToEvent event) {
        if (!InteractionVisualizer.eventDrivenBlockUpdates) {
            return;
        }
        markAffectedColumnDirty(event.getToBlock());
    }

    public void onAffectedBlockExplode(BlockExplodeEvent event) {
        if (!InteractionVisualizer.eventDrivenBlockUpdates) {
            return;
        }
        for (Block block : event.blockList()) {
            markAffectedColumnDirty(block);
        }
    }

    public void onAffectedEntityExplode(EntityExplodeEvent event) {
        if (!InteractionVisualizer.eventDrivenBlockUpdates) {
            return;
        }
        for (Block block : event.blockList()) {
            markAffectedColumnDirty(block);
        }
    }

    public void onDispenserHarvest(BlockDispenseEvent event) {
        if (!InteractionVisualizer.eventDrivenBlockUpdates) {
            return;
        }
        Material dispensed = event.getItem().getType();
        if (dispensed != Material.GLASS_BOTTLE && dispensed != Material.SHEARS) {
            return;
        }
        BlockData data = event.getBlock().getBlockData();
        if (data instanceof Directional directional) {
            markDirty(event.getBlock().getRelative(directional.getFacing()));
        }
    }

    public void onBeenestAdded(TileEntityAddedEvent event) {
        if (InteractionVisualizer.eventDrivenBlockUpdates
                && event.getTileEntityType() == TileEntityType.BEE_NEST) {
            blockUpdates.markDirty(event.getBlock(), (long) Bukkit.getCurrentTick() + 1L);
        }
    }

    public void onBeenestActivated(TileEntityActivatedEvent event) {
        if (InteractionVisualizer.eventDrivenBlockUpdates
                && event.getTileEntityType() == TileEntityType.BEE_NEST) {
            blockUpdates.markDirty(event.getBlock(), (long) Bukkit.getCurrentTick() + 1L);
        }
    }

    public void onBeenestDeactivated(TileEntityDeactivatedEvent event) {
        if (InteractionVisualizer.eventDrivenBlockUpdates
                && event.getTileEntityType() == TileEntityType.BEE_NEST) {
            removeTrackedDisplay(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBeeEnterBeenest(EntityEnterBlockEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Block block = event.getBlock();
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            if (!(event.getEntity() instanceof Bee) || block.getType() != Material.BEE_NEST
                    || !beenestMap.containsKey(block)) {
                return;
            }
            markDirty(block);
        } else {
            Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> measureLegacyUpdate(block), 1, block.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBeeLeaveBeenest(EntityChangeBlockEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Block block = event.getBlock();
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            markAffectedColumnDirty(block);
            if (!(event.getEntity() instanceof Bee) || block.getType() != Material.BEE_NEST
                    || !beenestMap.containsKey(block)) {
                return;
            }
            markDirty(block);
        } else {
            Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> measureLegacyUpdate(block), 1, block.getLocation());
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractBeenest(PlayerInteractEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Block block = event.getClickedBlock();
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            if (block == null) {
                return;
            }
            // Covers both harvesting the nest itself and toggling an
            // obstruction (for example a trapdoor) in the smoke column.
            markAffectedColumnDirty(block);
            markDirty(block);
        } else if (block != null) {
            Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> measureLegacyUpdate(block), 1, block.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakBeenest(TileEntityRemovedEvent event) {
        removeTrackedDisplay(event.getBlock());
    }

    private void removeTrackedDisplay(Block block) {
        blockUpdates.remove(block);
        Map<String, Object> map = beenestMap.remove(block);
        if (map == null) {
            return;
        }
        if (map.get("0") instanceof DisplayEntity) {
            DisplayEntity stand = (DisplayEntity) map.get("0");
            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
        }
        if (map.get("1") instanceof DisplayEntity) {
            DisplayEntity stand = (DisplayEntity) map.get("1");
            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
        }
    }

    public void updateBlock(Block block) {
        if (!isActive(block.getLocation())) {
            return;
        }
        if (!block.getType().equals(Material.BEE_NEST)) {
            return;
        }
        if (!beenestMap.containsKey(block)) {
            return;
        }

        Map<String, Object> map = beenestMap.get(block);

        org.bukkit.block.Beehive beehiveState = (org.bukkit.block.Beehive) block.getState();
        org.bukkit.block.data.type.Beehive beehiveData = (org.bukkit.block.data.type.Beehive) block.getBlockData();

        {
            DisplayEntity line0 = (DisplayEntity) map.get("0");
            DisplayEntity line1 = (DisplayEntity) map.get("1");

            String str0 = "";
            for (int i = 0; i < beehiveData.getHoneyLevel(); i++) {
                str0 += (beehiveState.isSedated() ? filledColor : noCampfireColor) + honeyLevelCharacter;
            }
            for (int i = beehiveData.getHoneyLevel(); i < beehiveData.getMaximumHoneyLevel(); i++) {
                str0 += emptyColor + honeyLevelCharacter;
            }
            String str1 = beeCountText.replace("{Current}", beehiveState.getEntityCount() + "").replace("{Max}", beehiveState.getMaxEntities() + "");

            if (!PlainTextComponentSerializer.plainText().serialize(line0.getCustomName()).equals(str0)) {
                line0.setCustomName(str0);
                line0.setCustomNameVisible(true);
                DisplayManager.updateDisplay(line0);
            }
            if (!PlainTextComponentSerializer.plainText().serialize(line1.getCustomName()).equals(str1)) {
                line1.setCustomName(str1);
                line1.setCustomNameVisible(true);
                DisplayManager.updateDisplay(line1);
            }
        }
    }

    public Set<Block> nearbyBeenest() {
        return TileEntityManager.getTileEntities(TileEntityType.BEE_NEST);
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
        Vector direction = target.toVector().subtract(origin.toVector()).multiply(0.7);

        Location loc0 = block.getLocation().clone().add(direction).add(0.5, 0.25, 0.5);
        loc0.setDirection(facing.getDirection());
        DisplayEntity line0 = new DisplayEntity(loc0.clone());
        setStand(line0);

        Location loc1 = block.getLocation().clone().add(direction).add(0.5, 0, 0.5);
        loc1.setDirection(facing.getDirection());
        DisplayEntity line1 = new DisplayEntity(loc1.clone());
        setStand(line1);

        map.put("0", line0);
        map.put("1", line1);

        DisplayManager.spawnDisplay(InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY), line0);
        DisplayManager.spawnDisplay(InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY), line1);

        return map;
    }

    public void setStand(DisplayEntity stand) {
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
