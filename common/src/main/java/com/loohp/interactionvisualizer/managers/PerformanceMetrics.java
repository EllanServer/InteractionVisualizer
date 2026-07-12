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

package com.loohp.interactionvisualizer.managers;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.loohp.interactionvisualizer.InteractionVisualizer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.Locale;

/**
 * Opt-in, allocation-free counters for reproducible live A/B measurements.
 *
 * <p>The hot-path methods deliberately compile down to one predictable branch
 * while sampling is disabled. They count only packet operations owned by this
 * plugin; external on-wire byte accounting remains the responsibility of a
 * packet capture tool.</p>
 */
public final class PerformanceMetrics implements Listener {

    private static final int MAX_TICK_SAMPLES = 72_000;
    private static final PerformanceMetrics INSTANCE = new PerformanceMetrics();

    private final double[] tickDurations = new double[MAX_TICK_SAMPLES];

    private volatile boolean collecting;
    private String label = "";
    private long startedNanos;
    private int tickCount;
    private long tickSamplesDropped;
    private long virtualSpawnBundles;
    private long virtualMotionBundles;
    private long virtualTeleportBundles;
    private long virtualRemovePackets;
    private long virtualPickupPackets;
    private long bukkitEntitySpawns;
    private long bukkitEntityRemoves;
    private long bukkitEntityTeleports;
    private long bukkitShowCalls;
    private long bukkitHideCalls;
    private long displaySyncs;
    private long itemSyncs;
    private long virtualViewerChecks;
    private long itemAnimationNanos;
    private long droppedItemNanos;

    private PerformanceMetrics() {
    }

    public static void register(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(INSTANCE, plugin);
    }

    public static boolean isCollecting() {
        return INSTANCE.collecting;
    }

    public static boolean start(String requestedLabel) {
        if (INSTANCE.collecting) {
            return false;
        }
        INSTANCE.label = sanitizeLabel(requestedLabel);
        INSTANCE.startedNanos = System.nanoTime();
        INSTANCE.tickCount = 0;
        INSTANCE.tickSamplesDropped = 0;
        INSTANCE.virtualSpawnBundles = 0;
        INSTANCE.virtualMotionBundles = 0;
        INSTANCE.virtualTeleportBundles = 0;
        INSTANCE.virtualRemovePackets = 0;
        INSTANCE.virtualPickupPackets = 0;
        INSTANCE.bukkitEntitySpawns = 0;
        INSTANCE.bukkitEntityRemoves = 0;
        INSTANCE.bukkitEntityTeleports = 0;
        INSTANCE.bukkitShowCalls = 0;
        INSTANCE.bukkitHideCalls = 0;
        INSTANCE.displaySyncs = 0;
        INSTANCE.itemSyncs = 0;
        INSTANCE.virtualViewerChecks = 0;
        INSTANCE.itemAnimationNanos = 0;
        INSTANCE.droppedItemNanos = 0;
        INSTANCE.collecting = true;
        return true;
    }

    public static Snapshot stop() {
        if (!INSTANCE.collecting) {
            return null;
        }
        INSTANCE.collecting = false;
        Snapshot snapshot = INSTANCE.createSnapshot();
        InteractionVisualizer.plugin.getLogger().info("IV_PERF " + snapshot.json());
        return snapshot;
    }

    public static Snapshot snapshot() {
        return INSTANCE.collecting ? INSTANCE.createSnapshot() : null;
    }

    public static void virtualSpawnBundle() {
        if (INSTANCE.collecting) {
            INSTANCE.virtualSpawnBundles++;
        }
    }

    public static void virtualMotionBundle() {
        if (INSTANCE.collecting) {
            INSTANCE.virtualMotionBundles++;
        }
    }

    public static void virtualTeleportBundle() {
        if (INSTANCE.collecting) {
            INSTANCE.virtualTeleportBundles++;
        }
    }

    public static void virtualRemovePacket() {
        if (INSTANCE.collecting) {
            INSTANCE.virtualRemovePackets++;
        }
    }

    public static void virtualPickupPacket() {
        if (INSTANCE.collecting) {
            INSTANCE.virtualPickupPackets++;
        }
    }

    public static void bukkitEntitySpawn() {
        if (INSTANCE.collecting) {
            INSTANCE.bukkitEntitySpawns++;
        }
    }

    public static void bukkitEntityRemove() {
        if (INSTANCE.collecting) {
            INSTANCE.bukkitEntityRemoves++;
        }
    }

    public static void bukkitEntityTeleport() {
        if (INSTANCE.collecting) {
            INSTANCE.bukkitEntityTeleports++;
        }
    }

    public static void bukkitShow() {
        if (INSTANCE.collecting) {
            INSTANCE.bukkitShowCalls++;
        }
    }

    public static void bukkitHide() {
        if (INSTANCE.collecting) {
            INSTANCE.bukkitHideCalls++;
        }
    }

    public static void displaySync() {
        if (INSTANCE.collecting) {
            INSTANCE.displaySyncs++;
        }
    }

    public static void itemSync() {
        if (INSTANCE.collecting) {
            INSTANCE.itemSyncs++;
        }
    }

    public static void virtualViewerChecks(int checks) {
        if (INSTANCE.collecting) {
            INSTANCE.virtualViewerChecks += checks;
        }
    }

    public static void itemAnimationNanos(long nanos) {
        if (INSTANCE.collecting) {
            INSTANCE.itemAnimationNanos += nanos;
        }
    }

    public static void droppedItemNanos(long nanos) {
        if (INSTANCE.collecting) {
            INSTANCE.droppedItemNanos += nanos;
        }
    }

    @EventHandler
    public void onServerTickEnd(ServerTickEndEvent event) {
        if (!collecting) {
            return;
        }
        if (tickCount < tickDurations.length) {
            // Paper exposes this duration in milliseconds.
            tickDurations[tickCount++] = event.getTickDuration();
        } else {
            tickSamplesDropped++;
        }
    }

    private Snapshot createSnapshot() {
        int samples = tickCount;
        double[] sorted = Arrays.copyOf(tickDurations, samples);
        Arrays.sort(sorted);
        double mean = 0.0D;
        long over50 = 0;
        for (double duration : sorted) {
            mean += duration;
            if (duration > 50.0D) {
                over50++;
            }
        }
        mean = samples == 0 ? 0.0D : mean / samples;
        long elapsedNanos = Math.max(1L, System.nanoTime() - startedNanos);
        return new Snapshot(label, elapsedNanos, samples, tickSamplesDropped,
                percentile(sorted, 0.50D), percentile(sorted, 0.95D), percentile(sorted, 0.99D),
                percentile(sorted, 0.999D), samples == 0 ? 0.0D : sorted[samples - 1], mean, over50,
                virtualSpawnBundles, virtualMotionBundles, virtualTeleportBundles, virtualRemovePackets,
                virtualPickupPackets, bukkitEntitySpawns, bukkitEntityRemoves, bukkitEntityTeleports,
                bukkitShowCalls, bukkitHideCalls, displaySyncs, itemSyncs, virtualViewerChecks,
                itemAnimationNanos, droppedItemNanos);
    }

    private static double percentile(double[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0.0D;
        }
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static String sanitizeLabel(String value) {
        if (value == null || value.isBlank()) {
            return "unnamed";
        }
        return value.replaceAll("[^A-Za-z0-9_.-]", "_").substring(0, Math.min(48, value.length()));
    }

    public record Snapshot(
            String label,
            long elapsedNanos,
            int tickSamples,
            long droppedTickSamples,
            double msptP50,
            double msptP95,
            double msptP99,
            double msptP999,
            double msptMax,
            double msptMean,
            long ticksOver50ms,
            long virtualSpawnBundles,
            long virtualMotionBundles,
            long virtualTeleportBundles,
            long virtualRemovePackets,
            long virtualPickupPackets,
            long bukkitEntitySpawns,
            long bukkitEntityRemoves,
            long bukkitEntityTeleports,
            long bukkitShowCalls,
            long bukkitHideCalls,
            long displaySyncs,
            long itemSyncs,
            long virtualViewerChecks,
            long itemAnimationNanos,
            long droppedItemNanos) {

        public double seconds() {
            return elapsedNanos / 1_000_000_000.0D;
        }

        /** Sparrow outer packets are exact; Bukkit operations remain separately reported. */
        public long knownVirtualPackets() {
            return virtualSpawnBundles + virtualMotionBundles + virtualTeleportBundles
                    + virtualRemovePackets + virtualPickupPackets;
        }

        public String summary() {
            return String.format(Locale.ROOT,
                    "label=%s samples=%d p50/p95/p99=%.3f/%.3f/%.3fms virtualPackets=%d anchors=%d/%d/%d",
                    label, tickSamples, msptP50, msptP95, msptP99, knownVirtualPackets(),
                    bukkitEntitySpawns, bukkitEntityTeleports, bukkitEntityRemoves);
        }

        public String json() {
            return String.format(Locale.ROOT,
                    "{\"label\":\"%s\",\"seconds\":%.3f,\"tickSamples\":%d," +
                            "\"droppedTickSamples\":%d,\"msptP50\":%.6f,\"msptP95\":%.6f," +
                            "\"msptP99\":%.6f,\"msptP999\":%.6f,\"msptMax\":%.6f," +
                            "\"msptMean\":%.6f,\"ticksOver50ms\":%d," +
                            "\"virtualSpawnBundles\":%d,\"virtualMotionBundles\":%d," +
                            "\"virtualTeleportBundles\":%d,\"virtualRemovePackets\":%d," +
                            "\"virtualPickupPackets\":%d,\"knownVirtualPackets\":%d," +
                            "\"bukkitEntitySpawns\":%d,\"bukkitEntityRemoves\":%d," +
                            "\"bukkitEntityTeleports\":%d,\"bukkitShowCalls\":%d," +
                            "\"bukkitHideCalls\":%d,\"displaySyncs\":%d,\"itemSyncs\":%d," +
                            "\"virtualViewerChecks\":%d,\"itemAnimationMs\":%.6f,\"droppedItemMs\":%.6f}",
                    label, seconds(), tickSamples, droppedTickSamples, msptP50, msptP95, msptP99,
                    msptP999, msptMax, msptMean, ticksOver50ms, virtualSpawnBundles,
                    virtualMotionBundles, virtualTeleportBundles, virtualRemovePackets, virtualPickupPackets,
                    knownVirtualPackets(), bukkitEntitySpawns, bukkitEntityRemoves, bukkitEntityTeleports,
                    bukkitShowCalls, bukkitHideCalls, displaySyncs, itemSyncs, virtualViewerChecks,
                    itemAnimationNanos / 1_000_000.0D, droppedItemNanos / 1_000_000.0D);
        }
    }
}
