/*
 * This file is part of InteractionVisualizer.
 *
 * Copyright (C) 2026. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.loohp.interactionvisualizer.integration.craftengine;

import com.loohp.interactionvisualizer.objectholders.ILightManager;
import com.loohp.interactionvisualizer.objectholders.LightType;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.entity.furniture.behavior.GlowingFurnitureBehaviorTemplate;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.world.BukkitWorld;
import net.momirealms.craftengine.core.block.BlockKeys;
import net.momirealms.craftengine.core.block.BlockStateWrapper;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureLightData;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * CraftEngine-backed light blocks. This class is reflection-loaded and is the
 * only light implementation allowed to link against CraftEngine internals.
 *
 * <p>InteractionVisualizer contributes its levels to CraftEngine's shared
 * furniture light registry. That preserves the strongest light when CE
 * furniture and an IV display occupy the same block. SKY and BLOCK requests
 * are coalesced to their strongest value because vanilla light blocks expose a
 * single block-light channel.</p>
 */
public final class CraftEngineLightManager implements ILightManager, Listener {

    private final Plugin plugin;
    private final Object lock = new Object();
    private final Map<LightKey, RequestedLightLevels> desired = new HashMap<>();
    private final Map<LightKey, Integer> applied = new HashMap<>();
    private final Map<LightKey, Integer> externalBaseline = new HashMap<>();
    private final Set<LightKey> dirty = new HashSet<>();
    private final Set<LightKey> waitingForChunk = new HashSet<>();
    private final Set<LightKey> restoreOnLoad = new HashSet<>();
    private final Map<ChunkKey, Set<LightKey>> trackedByChunk = new HashMap<>();

    private long updatePeriod;
    private ScheduledTask flushTask;
    private boolean closed;
    private boolean linkageFailureReported;

    public CraftEngineLightManager(Plugin plugin, long updatePeriod) {
        this.plugin = plugin;
        this.updatePeriod = sanitizePeriod(updatePeriod);
        verifyProviderShape();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void createLight(Location location, int lightLevel, LightType lightType) {
        LightKey key = LightKey.from(location);
        if (key == null || lightType == null) {
            return;
        }
        int sanitizedLevel = Math.max(0, Math.min(15, lightLevel));
        synchronized (lock) {
            if (closed) {
                return;
            }
            RequestedLightLevels previous = desired.getOrDefault(key, RequestedLightLevels.NONE);
            RequestedLightLevels updated = previous.with(lightType, sanitizedLevel);
            if (updated.equals(previous)) {
                return;
            }
            if (updated.effective() == 0) {
                desired.remove(key);
            } else {
                desired.put(key, updated);
            }
            trackLocked(key);
            dirty.add(key);
            scheduleLocked();
        }
    }

    @Override
    public void deleteLight(Location location) {
        LightKey key = LightKey.from(location);
        if (key == null) {
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            boolean hadDesired = desired.remove(key) != null;
            if (!hadDesired && !applied.containsKey(key) && !waitingForChunk.contains(key)
                    && !restoreOnLoad.contains(key)) {
                return;
            }
            trackLocked(key);
            dirty.add(key);
            scheduleLocked();
        }
    }

    @Override
    public ScheduledTask run() {
        synchronized (lock) {
            if (!dirty.isEmpty()) {
                scheduleLocked();
            }
            return flushTask;
        }
    }

    @Override
    public void setUpdatePeriod(long updatePeriod) {
        synchronized (lock) {
            this.updatePeriod = sanitizePeriod(updatePeriod);
        }
    }

    private void scheduleLocked() {
        if (!closed && flushTask == null) {
            flushTask = Scheduler.runTaskLater(plugin, this::flush, updatePeriod);
        }
    }

    private void flush() {
        Set<LightKey> batch;
        synchronized (lock) {
            flushTask = null;
            if (closed) {
                return;
            }
            batch = Set.copyOf(dirty);
            dirty.clear();
        }

        for (LightKey key : batch) {
            try {
                apply(key);
            } catch (LinkageError error) {
                disableAfterLinkageFailure(error);
                return;
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not update a CraftEngine light at " + key, exception);
            }
        }

        synchronized (lock) {
            if (!dirty.isEmpty()) {
                scheduleLocked();
            }
        }
    }

    private void apply(LightKey key) {
        World world = Bukkit.getWorld(key.worldId());
        if (world == null || !world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) {
            synchronized (lock) {
                waitingForChunk.add(key);
                trackLocked(key);
            }
            return;
        }

        int target;
        Integer previous;
        boolean restore;
        synchronized (lock) {
            target = desired.getOrDefault(key, RequestedLightLevels.NONE).effective();
            previous = applied.get(key);
            restore = restoreOnLoad.contains(key);
            waitingForChunk.remove(key);
        }

        BukkitWorld craftWorld = BukkitAdaptor.adapt(world);
        BlockPos blockPos = key.toBlockPos();
        FurnitureLightData lightData = lightData(key.worldId(), target > 0 || previous != null);
        int baseline = captureExternalBaseline(key, craftWorld, blockPos, lightData);
        boolean changed = false;

        if (previous != null && previous != target) {
            if (lightData != null) {
                lightData.removeLightData(blockPos, previous);
            }
            changed = true;
        }
        if (target > 0 && (previous == null || previous != target)) {
            lightData = lightData == null ? lightData(key.worldId(), true) : lightData;
            lightData.addLightData(blockPos, target);
            changed = true;
        }

        synchronized (lock) {
            if (target == 0) {
                applied.remove(key);
            } else {
                applied.put(key, target);
            }
        }

        if (changed || restore) {
            int effective = lightData == null ? 0 : lightData.getLightPower(blockPos);
            updateServerLightBlock(craftWorld, key, Math.max(effective, baseline));
        }

        synchronized (lock) {
            restoreOnLoad.remove(key);
            cleanupKeyLocked(key);
        }
    }

    private int captureExternalBaseline(LightKey key, BukkitWorld world, BlockPos blockPos,
                                        FurnitureLightData lightData) {
        synchronized (lock) {
            Integer baseline = externalBaseline.get(key);
            if (baseline != null) {
                return baseline;
            }
        }

        int baseline = 0;
        BlockStateWrapper state = world.getBlockState(key.x(), key.y(), key.z());
        int managedLevel = lightData == null ? 0 : lightData.getLightPower(blockPos);
        if (managedLevel == 0 && BlockKeys.LIGHT.equals(state.ownerId())) {
            Object value = state.getProperty("level");
            if (value instanceof Number number) {
                baseline = number.intValue();
            } else if (value != null) {
                try {
                    baseline = Integer.parseInt(value.toString());
                } catch (NumberFormatException ignored) {
                    baseline = 0;
                }
            }
        }
        baseline = Math.max(0, Math.min(15, baseline));
        synchronized (lock) {
            Integer existing = externalBaseline.get(key);
            if (existing != null) {
                return existing;
            }
            externalBaseline.put(key, baseline);
            return baseline;
        }
    }

    private void updateServerLightBlock(BukkitWorld world, LightKey key, int level) {
        int sanitizedLevel = Math.max(0, Math.min(15, level));
        BlockStateWrapper state = world.getBlockState(key.x(), key.y(), key.z());
        int stateId = state.registryId();
        BlockStateWrapper target = null;
        if (stateId == GlowingFurnitureBehaviorTemplate.AIR_BLOCK_STATE_ID) {
            target = BlockStateUtils.toBlockStateWrapper(
                    GlowingFurnitureBehaviorTemplate.LIGHT_BLOCK_STATES[sanitizedLevel]);
        } else if (stateId == GlowingFurnitureBehaviorTemplate.WATER_BLOCK_STATE_ID) {
            target = BlockStateUtils.toBlockStateWrapper(
                    GlowingFurnitureBehaviorTemplate.WATERLOGGED_LIGHT_BLOCK_STATES[sanitizedLevel]);
        } else if (BlockKeys.LIGHT.equals(state.ownerId())) {
            if (sanitizedLevel == 0) {
                boolean waterlogged = Boolean.TRUE.equals(state.getProperty("waterlogged"));
                Object vanillaState = waterlogged
                        ? GlowingFurnitureBehaviorTemplate.WATERLOGGED_LIGHT_BLOCK_STATES[0]
                        : GlowingFurnitureBehaviorTemplate.LIGHT_BLOCK_STATES[0];
                target = BlockStateUtils.toBlockStateWrapper(vanillaState);
            } else {
                target = state.withProperty("level", Integer.toString(sanitizedLevel));
            }
        }
        if (target != null) {
            world.setBlockState(key.x(), key.y(), key.z(), target, UpdateFlags.UPDATE_ALL);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        ChunkKey chunkKey = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        List<LightKey> keys;
        synchronized (lock) {
            Set<LightKey> tracked = trackedByChunk.get(chunkKey);
            if (closed || tracked == null || tracked.isEmpty()) {
                return;
            }
            keys = List.copyOf(tracked);
        }

        for (LightKey key : keys) {
            Integer previous;
            synchronized (lock) {
                previous = applied.remove(key);
                if (previous != null) {
                    restoreOnLoad.add(key);
                }
                if (desired.containsKey(key) || previous != null) {
                    waitingForChunk.add(key);
                }
            }
            if (previous != null) {
                FurnitureLightData data = lightData(key.worldId(), false);
                if (data != null) {
                    data.removeLightData(key.toBlockPos(), previous);
                }
            }
            synchronized (lock) {
                cleanupKeyLocked(key);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        ChunkKey chunkKey = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        synchronized (lock) {
            Set<LightKey> tracked = trackedByChunk.get(chunkKey);
            if (closed || tracked == null || tracked.isEmpty()) {
                return;
            }
            for (LightKey key : tracked) {
                if (desired.containsKey(key) || waitingForChunk.contains(key) || restoreOnLoad.contains(key)) {
                    dirty.add(key);
                }
            }
            if (!dirty.isEmpty()) {
                scheduleLocked();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID worldId = event.getWorld().getUID();
        List<LightKey> keys;
        synchronized (lock) {
            if (closed) {
                return;
            }
            keys = applied.keySet().stream().filter(key -> key.worldId().equals(worldId)).toList();
        }
        for (LightKey key : keys) {
            Integer previous;
            synchronized (lock) {
                previous = applied.remove(key);
                if (previous != null) {
                    restoreOnLoad.add(key);
                }
                waitingForChunk.add(key);
            }
            if (previous != null) {
                FurnitureLightData data = lightData(worldId, false);
                if (data != null) {
                    data.removeLightData(key.toBlockPos(), previous);
                }
            }
        }
    }

    @Override
    public void shutdown() {
        List<Map.Entry<LightKey, Integer>> active;
        Map<LightKey, Integer> baselines;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            if (flushTask != null) {
                flushTask.cancel();
                flushTask = null;
            }
            active = new ArrayList<>(applied.entrySet());
            baselines = Map.copyOf(externalBaseline);
        }
        HandlerList.unregisterAll(this);

        for (Map.Entry<LightKey, Integer> entry : active) {
            LightKey key = entry.getKey();
            try {
                FurnitureLightData data = lightData(key.worldId(), false);
                BlockPos blockPos = key.toBlockPos();
                if (data != null) {
                    data.removeLightData(blockPos, entry.getValue());
                }
                World world = Bukkit.getWorld(key.worldId());
                if (world != null && world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) {
                    int remaining = data == null ? 0 : data.getLightPower(blockPos);
                    updateServerLightBlock(BukkitAdaptor.adapt(world), key,
                            Math.max(remaining, baselines.getOrDefault(key, 0)));
                }
            } catch (LinkageError | RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not restore a CraftEngine light while shutting down", exception);
            }
        }

        synchronized (lock) {
            desired.clear();
            applied.clear();
            externalBaseline.clear();
            dirty.clear();
            waitingForChunk.clear();
            restoreOnLoad.clear();
            trackedByChunk.clear();
        }
    }

    private void disableAfterLinkageFailure(LinkageError error) {
        synchronized (lock) {
            closed = true;
            desired.clear();
            applied.clear();
            externalBaseline.clear();
            dirty.clear();
            waitingForChunk.clear();
            restoreOnLoad.clear();
            trackedByChunk.clear();
            if (!linkageFailureReported) {
                linkageFailureReported = true;
                plugin.getLogger().log(Level.WARNING,
                        "CraftEngine lighting was disabled after an API linkage failure", error);
            }
        }
        HandlerList.unregisterAll(this);
    }

    private FurnitureLightData lightData(UUID worldId, boolean create) {
        if (create) {
            return GlowingFurnitureBehaviorTemplate.LIGHT_DATA.computeIfAbsent(
                    worldId, ignored -> new FurnitureLightData());
        }
        return GlowingFurnitureBehaviorTemplate.LIGHT_DATA.get(worldId);
    }

    private void trackLocked(LightKey key) {
        trackedByChunk.computeIfAbsent(key.chunk(), ignored -> new HashSet<>()).add(key);
    }

    private void cleanupKeyLocked(LightKey key) {
        if (desired.containsKey(key) || applied.containsKey(key) || waitingForChunk.contains(key)
                || restoreOnLoad.contains(key)) {
            return;
        }
        externalBaseline.remove(key);
        Set<LightKey> tracked = trackedByChunk.get(key.chunk());
        if (tracked != null) {
            tracked.remove(key);
            if (tracked.isEmpty()) {
                trackedByChunk.remove(key.chunk());
            }
        }
    }

    private static void verifyProviderShape() {
        if (GlowingFurnitureBehaviorTemplate.LIGHT_BLOCK_STATES.length != 16
                || GlowingFurnitureBehaviorTemplate.WATERLOGGED_LIGHT_BLOCK_STATES.length != 16) {
            throw new IllegalStateException("The installed CraftEngine light-state API is not compatible");
        }
    }

    private static long sanitizePeriod(long updatePeriod) {
        return Math.max(1L, updatePeriod);
    }

    private record ChunkKey(UUID worldId, int x, int z) {
    }

    private record LightKey(UUID worldId, int x, int y, int z) {

        private static LightKey from(Location location) {
            if (location == null || location.getWorld() == null) {
                return null;
            }
            return new LightKey(location.getWorld().getUID(), location.getBlockX(),
                    location.getBlockY(), location.getBlockZ());
        }

        private ChunkKey chunk() {
            return new ChunkKey(worldId, x >> 4, z >> 4);
        }

        private BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }
}
