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

package com.loohp.interactionvisualizer.blocks;

import com.loohp.interactionvisualizer.InteractionVisualizer;
import com.loohp.interactionvisualizer.managers.PerformanceMetrics;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One main-thread heartbeat for every built-in autonomous block display.
 * Events enqueue exact material lanes, active timers rotate at their existing
 * CheckingPeriod, and each lane keeps a bounded low-frequency safety audit.
 */
public final class BlockUpdateCoordinator {

    private static final Map<Object, Lane> LANES = new IdentityHashMap<>();
    private static final Map<Material, List<Lane>> LANES_BY_MATERIAL = new EnumMap<>(Material.class);

    private static ScheduledTask heartbeat;

    private BlockUpdateCoordinator() {
    }

    static void register(Object owner, Collection<Material> materials,
                         Supplier<? extends Collection<Block>> auditSource,
                         int activePeriod, int auditPeriod,
                         BlockUpdateScheduler.Updater<Block> updater,
                         Consumer<Block> remover) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(materials, "materials");
        if (LANES.containsKey(owner)) {
            return;
        }
        Lane lane = new Lane(Set.copyOf(materials), auditSource,
                activePeriod, auditPeriod, updater, remover);
        LANES.put(owner, lane);
        for (Material material : lane.materials) {
            LANES_BY_MATERIAL.computeIfAbsent(material, ignored -> new ArrayList<>()).add(lane);
        }
        if (heartbeat == null || heartbeat.isCancelled()) {
            heartbeat = Scheduler.runTaskTimer(InteractionVisualizer.plugin,
                    BlockUpdateCoordinator::tick, 0L, 1L);
        }
    }

    static Set<Material> materialsMatching(Predicate<Material> predicate) {
        EnumSet<Material> materials = EnumSet.noneOf(Material.class);
        for (Material material : Material.values()) {
            if (predicate.test(material)) {
                materials.add(material);
            }
        }
        return materials;
    }

    static void markDirty(Object owner, Block block, long readyTick) {
        Lane lane = LANES.get(owner);
        if (lane != null && block != null && lane.materials.contains(block.getType())) {
            lane.scheduler.markDirty(block, readyTick);
        }
    }

    static void markDirtyUnlessActive(Object owner, Block block, long readyTick) {
        Lane lane = LANES.get(owner);
        if (lane != null && block != null && lane.materials.contains(block.getType())) {
            lane.scheduler.markDirtyUnlessActive(block, readyTick);
        }
    }

    static void remove(Object owner, Block block) {
        Lane lane = LANES.get(owner);
        if (lane != null) {
            lane.remove(block);
        }
    }

    /** Marks every registered display lane matching the block's current material. */
    public static void markDirty(Block block) {
        markDirty(block, (long) Bukkit.getCurrentTick() + 1L);
    }

    public static void markDirty(Block block, long readyTick) {
        if (block == null) {
            return;
        }
        List<Lane> lanes = LANES_BY_MATERIAL.get(block.getType());
        if (lanes != null) {
            for (Lane lane : lanes) {
                lane.scheduler.markDirty(block, readyTick);
            }
        }
    }

    public static void markDirtyUnlessActive(Block block) {
        if (block == null) {
            return;
        }
        List<Lane> lanes = LANES_BY_MATERIAL.get(block.getType());
        if (lanes != null) {
            long readyTick = (long) Bukkit.getCurrentTick() + 1L;
            for (Lane lane : lanes) {
                lane.scheduler.markDirtyUnlessActive(block, readyTick);
            }
        }
    }

    /** Invalidates a block in every lane, including after its material changed. */
    public static void remove(Block block) {
        if (block == null) {
            return;
        }
        for (Lane lane : LANES.values()) {
            lane.remove(block);
        }
    }

    public static boolean isEmpty() {
        return LANES.isEmpty();
    }

    public static boolean tracks(Material material) {
        List<Lane> lanes = LANES_BY_MATERIAL.get(material);
        return lanes != null && !lanes.isEmpty();
    }

    public static int retainedLaneCount() {
        return LANES.size();
    }

    public static int pendingDirtyCount() {
        int count = 0;
        for (Lane lane : LANES.values()) {
            count += lane.scheduler.pendingDirtyCount();
        }
        return count;
    }

    public static int activeCount() {
        int count = 0;
        for (Lane lane : LANES.values()) {
            count += lane.scheduler.activeCount();
        }
        return count;
    }

    public static void shutdown() {
        ScheduledTask task = heartbeat;
        heartbeat = null;
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
        LANES.clear();
        LANES_BY_MATERIAL.clear();
    }

    private static void tick() {
        boolean collecting = PerformanceMetrics.isCollecting();
        long started = collecting ? System.nanoTime() : 0L;
        int checks = 0;
        long tick = Bukkit.getCurrentTick();
        for (Lane lane : LANES.values()) {
            checks += lane.scheduler.tick(tick,
                    InteractionVisualizer.blockUpdateMaxDirtyPerTick, lane.updater);
        }
        if (collecting) {
            PerformanceMetrics.blockUpdateChecks(checks, System.nanoTime() - started);
            PerformanceMetrics.blockUpdateQueues(
                    LANES.size(), pendingDirtyCount(), activeCount());
        }
    }

    private static final class Lane {

        private final Set<Material> materials;
        private final BlockUpdateScheduler<Block> scheduler;
        private final BlockUpdateScheduler.Updater<Block> updater;
        private final Consumer<Block> remover;

        private Lane(Set<Material> materials,
                     Supplier<? extends Collection<Block>> auditSource,
                     int activePeriod, int auditPeriod,
                     BlockUpdateScheduler.Updater<Block> updater,
                     Consumer<Block> remover) {
            this.materials = materials;
            this.scheduler = new BlockUpdateScheduler<>(auditSource, activePeriod, auditPeriod);
            this.updater = Objects.requireNonNull(updater, "updater");
            this.remover = Objects.requireNonNull(remover, "remover");
        }

        private void remove(Block block) {
            if (block == null) {
                return;
            }
            scheduler.remove(block);
            remover.accept(block);
        }
    }
}
