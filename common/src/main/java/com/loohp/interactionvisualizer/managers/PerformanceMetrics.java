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
import com.loohp.interactionvisualizer.utils.LegacyTextComponentCache;
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
    private final SlowestTickTracker slowestTickTracker = new SlowestTickTracker();

    private volatile boolean collecting;
    private String label = "";
    private boolean configStaticAnchor;
    private boolean configPacketOnlyStatic;
    private boolean configHideIfViewObstructed;
    private boolean configVisibilityRateLimit;
    private int configVisibilityBucketSize;
    private int configVisibilityRestorePerTick;
    private DroppedLabelVisibilityConfig droppedLabelVisibilityConfig =
            new DroppedLabelVisibilityConfig(false, 64, false, 128, 32);
    private DroppedLabelVisibilityConfig configDroppedLabelVisibility = droppedLabelVisibilityConfig;
    private boolean configEventDrivenBlockUpdates;
    private int configBlockUpdateMaxDirtyPerTick;
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
    private long packetOnlyItemSyncs;
    private long virtualViewerChecks;
    private long visibilityShowsQueued;
    private long visibilityShowsDrained;
    private long viewerFullReconciles;
    private long viewerCandidates;
    private long craftEngineCullingCandidates;
    private long craftEngineCullingShowDecisions;
    private long craftEngineCullingHideDecisions;
    private long itemAnimationNanos;
    private long droppedItemNanos;
    private long blockUpdateChecks;
    private long blockUpdateNanos;
    private int blockUpdateCoordinatorLanesMax;
    private int blockUpdateDirtyQueueMax;
    private int blockUpdateActiveQueueMax;
    private long preferenceIoOperations;
    private long preferenceIoFailures;
    private int preferenceIoQueueDepthMax;
    private long preferenceSqlStatements;
    private long preferenceDatabaseReconnects;

    private PerformanceMetrics() {
    }

    public static void register(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(INSTANCE, plugin);
    }

    public static boolean isCollecting() {
        return INSTANCE.collecting;
    }

    public static void droppedLabelVisibilityConfig(boolean cullingEnabled,
                                                     int viewDistance,
                                                     boolean rateLimitEnabled,
                                                     int bucketSize,
                                                     int restorePerTick) {
        INSTANCE.droppedLabelVisibilityConfig = new DroppedLabelVisibilityConfig(
                cullingEnabled, viewDistance, rateLimitEnabled, bucketSize, restorePerTick);
    }

    public static boolean start(String requestedLabel) {
        if (INSTANCE.collecting) {
            return false;
        }
        INSTANCE.label = sanitizeLabel(requestedLabel);
        INSTANCE.configStaticAnchor = InteractionVisualizer.staticVirtualItemAnchorsDuringAnimation;
        INSTANCE.configPacketOnlyStatic = InteractionVisualizer.packetOnlyStaticVirtualItems;
        INSTANCE.configHideIfViewObstructed = InteractionVisualizer.hideIfObstructed
                && InteractionVisualizer.viewerCullingManager.enabled();
        INSTANCE.configVisibilityRateLimit = InteractionVisualizer.visibilityRateLimiting;
        INSTANCE.configVisibilityBucketSize = InteractionVisualizer.visibilityRateLimitBucketSize;
        INSTANCE.configVisibilityRestorePerTick = InteractionVisualizer.visibilityRateLimitRestorePerTick;
        INSTANCE.configDroppedLabelVisibility = INSTANCE.droppedLabelVisibilityConfig;
        INSTANCE.configEventDrivenBlockUpdates = InteractionVisualizer.eventDrivenBlockUpdates;
        INSTANCE.configBlockUpdateMaxDirtyPerTick = InteractionVisualizer.blockUpdateMaxDirtyPerTick;
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
        INSTANCE.packetOnlyItemSyncs = 0;
        INSTANCE.virtualViewerChecks = 0;
        INSTANCE.visibilityShowsQueued = 0;
        INSTANCE.visibilityShowsDrained = 0;
        INSTANCE.viewerFullReconciles = 0;
        INSTANCE.viewerCandidates = 0;
        INSTANCE.craftEngineCullingCandidates = 0;
        INSTANCE.craftEngineCullingShowDecisions = 0;
        INSTANCE.craftEngineCullingHideDecisions = 0;
        INSTANCE.itemAnimationNanos = 0;
        INSTANCE.droppedItemNanos = 0;
        INSTANCE.blockUpdateChecks = 0;
        INSTANCE.blockUpdateNanos = 0;
        INSTANCE.blockUpdateCoordinatorLanesMax = 0;
        INSTANCE.blockUpdateDirtyQueueMax = 0;
        INSTANCE.blockUpdateActiveQueueMax = 0;
        INSTANCE.preferenceIoOperations = 0;
        INSTANCE.preferenceIoFailures = 0;
        INSTANCE.preferenceIoQueueDepthMax = 0;
        INSTANCE.preferenceSqlStatements = 0;
        INSTANCE.preferenceDatabaseReconnects = 0;
        INSTANCE.slowestTickTracker.reset();
        LegacyTextComponentCache.startMeasurement();
        INSTANCE.collecting = true;
        return true;
    }

    public static Snapshot stop() {
        if (!INSTANCE.collecting) {
            return null;
        }
        INSTANCE.collecting = false;
        LegacyTextComponentCache.CacheMetrics textCache = LegacyTextComponentCache.stopMeasurement();
        Snapshot snapshot = INSTANCE.createSnapshot(textCache);
        InteractionVisualizer.plugin.getLogger().info("IV_PERF " + snapshot.json());
        return snapshot;
    }

    public static Snapshot snapshot() {
        return INSTANCE.collecting
                ? INSTANCE.createSnapshot(LegacyTextComponentCache.metrics())
                : null;
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

    public static void packetOnlyItemSync() {
        if (INSTANCE.collecting) {
            INSTANCE.packetOnlyItemSyncs++;
        }
    }

    public static void virtualViewerChecks(int checks) {
        if (INSTANCE.collecting) {
            INSTANCE.virtualViewerChecks += checks;
        }
    }

    public static void visibilityShowQueued() {
        if (INSTANCE.collecting) {
            INSTANCE.visibilityShowsQueued++;
        }
    }

    public static void visibilityShowDrained() {
        if (INSTANCE.collecting) {
            INSTANCE.visibilityShowsDrained++;
        }
    }

    public static void viewerReconcile(int candidates) {
        if (INSTANCE.collecting) {
            INSTANCE.viewerFullReconciles++;
            INSTANCE.viewerCandidates += candidates;
        }
    }

    public static void craftEngineCullingCandidate() {
        if (INSTANCE.collecting) {
            INSTANCE.craftEngineCullingCandidates++;
        }
    }

    public static void craftEngineCullingDecision(boolean visible) {
        if (INSTANCE.collecting) {
            if (visible) {
                INSTANCE.craftEngineCullingShowDecisions++;
            } else {
                INSTANCE.craftEngineCullingHideDecisions++;
            }
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

    public static void blockUpdateChecks(int checks, long nanos) {
        if (INSTANCE.collecting) {
            INSTANCE.blockUpdateChecks += checks;
            INSTANCE.blockUpdateNanos += nanos;
            INSTANCE.slowestTickTracker.blockUpdateChecks(checks, nanos);
        }
    }

    public static void blockUpdateQueues(int lanes, int dirty, int active) {
        if (INSTANCE.collecting) {
            INSTANCE.blockUpdateCoordinatorLanesMax = Math.max(
                    INSTANCE.blockUpdateCoordinatorLanesMax, lanes);
            INSTANCE.blockUpdateDirtyQueueMax = Math.max(INSTANCE.blockUpdateDirtyQueueMax, dirty);
            INSTANCE.blockUpdateActiveQueueMax = Math.max(INSTANCE.blockUpdateActiveQueueMax, active);
        }
    }

    public static void preferenceIoOperation() {
        if (INSTANCE.collecting) {
            synchronized (INSTANCE) {
                INSTANCE.preferenceIoOperations++;
            }
        }
    }

    public static void preferenceIoFailure() {
        if (INSTANCE.collecting) {
            synchronized (INSTANCE) {
                INSTANCE.preferenceIoFailures++;
            }
        }
    }

    public static void preferenceIoQueueDepth(int depth) {
        if (INSTANCE.collecting) {
            synchronized (INSTANCE) {
                INSTANCE.preferenceIoQueueDepthMax = Math.max(
                        INSTANCE.preferenceIoQueueDepthMax, depth);
            }
        }
    }

    public static void preferenceSqlStatement() {
        if (INSTANCE.collecting) {
            synchronized (INSTANCE) {
                INSTANCE.preferenceSqlStatements++;
            }
        }
    }

    public static void preferenceDatabaseReconnect() {
        if (INSTANCE.collecting) {
            synchronized (INSTANCE) {
                INSTANCE.preferenceDatabaseReconnects++;
            }
        }
    }

    @EventHandler
    public void onServerTickEnd(ServerTickEndEvent event) {
        if (!collecting) {
            return;
        }
        if (tickCount < tickDurations.length) {
            // Paper exposes this duration in milliseconds.
            double duration = event.getTickDuration();
            tickDurations[tickCount++] = duration;
            slowestTickTracker.completeTick(Bukkit.getCurrentTick(), duration);
        } else {
            tickSamplesDropped++;
            slowestTickTracker.discardTick();
        }
    }

    private Snapshot createSnapshot(LegacyTextComponentCache.CacheMetrics textCache) {
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
        int retainedCullingRegistrations = InteractionVisualizer.viewerCullingManager.retainedRegistrations();
        return new Snapshot(label, configStaticAnchor, configPacketOnlyStatic, configHideIfViewObstructed,
                configVisibilityRateLimit,
                configVisibilityBucketSize, configVisibilityRestorePerTick, configDroppedLabelVisibility,
                configEventDrivenBlockUpdates,
                configBlockUpdateMaxDirtyPerTick, LegacyTextComponentCache.isEnabled(),
                elapsedNanos, samples, tickSamplesDropped,
                percentile(sorted, 0.50D), percentile(sorted, 0.95D), percentile(sorted, 0.99D),
                percentile(sorted, 0.999D), samples == 0 ? 0.0D : sorted[samples - 1],
                slowestTickTracker.slowestBukkitTick(), slowestTickTracker.slowestEndEpochMillis(),
                slowestTickTracker.slowestBlockUpdateChecks(),
                slowestTickTracker.slowestBlockUpdateNanos(), mean, over50,
                virtualSpawnBundles, virtualMotionBundles, virtualTeleportBundles, virtualRemovePackets,
                virtualPickupPackets, bukkitEntitySpawns, bukkitEntityRemoves, bukkitEntityTeleports,
                bukkitShowCalls, bukkitHideCalls, displaySyncs, itemSyncs, packetOnlyItemSyncs,
                virtualViewerChecks, visibilityShowsQueued, visibilityShowsDrained,
                viewerFullReconciles, viewerCandidates, craftEngineCullingCandidates,
                craftEngineCullingShowDecisions, craftEngineCullingHideDecisions,
                retainedCullingRegistrations,
                itemAnimationNanos, droppedItemNanos, blockUpdateChecks, blockUpdateNanos,
                blockUpdateCoordinatorLanesMax, blockUpdateDirtyQueueMax, blockUpdateActiveQueueMax,
                preferenceIoOperations, preferenceIoFailures, preferenceIoQueueDepthMax,
                preferenceSqlStatements, preferenceDatabaseReconnects,
                textCache.requests(), textCache.misses(), textCache.sameRawFastPaths());
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
        String sanitized = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return sanitized.substring(0, Math.min(48, sanitized.length()));
    }

    public record DroppedLabelVisibilityConfig(
            boolean cullingEnabled,
            int viewDistance,
            boolean rateLimitEnabled,
            int bucketSize,
            int restorePerTick) {
    }

    public record Snapshot(
            String label,
            boolean staticAnchorDuringAnimation,
            boolean packetOnlyStatic,
            boolean hideIfViewObstructed,
            boolean visibilityRateLimit,
            int visibilityBucketSize,
            int visibilityRestorePerTick,
            DroppedLabelVisibilityConfig droppedLabelVisibility,
            boolean eventDrivenBlockUpdates,
            int blockUpdateMaxDirtyPerTick,
            boolean legacyTextComponentCache,
            long elapsedNanos,
            int tickSamples,
            long droppedTickSamples,
            double msptP50,
            double msptP95,
            double msptP99,
            double msptP999,
            double msptMax,
            int msptMaxBukkitTick,
            long msptMaxEndEpochMillis,
            long msptMaxBlockUpdateChecks,
            long msptMaxBlockUpdateNanos,
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
            long packetOnlyItemSyncs,
            long virtualViewerChecks,
            long visibilityShowsQueued,
            long visibilityShowsDrained,
            long viewerFullReconciles,
            long viewerCandidates,
            long craftEngineCullingCandidates,
            long craftEngineCullingShowDecisions,
            long craftEngineCullingHideDecisions,
            int craftEngineCullingRetainedRegistrations,
            long itemAnimationNanos,
            long droppedItemNanos,
            long blockUpdateChecks,
            long blockUpdateNanos,
            int blockUpdateCoordinatorLanesMax,
            int blockUpdateDirtyQueueMax,
            int blockUpdateActiveQueueMax,
            long preferenceIoOperations,
            long preferenceIoFailures,
            int preferenceIoQueueDepthMax,
            long preferenceSqlStatements,
            long preferenceDatabaseReconnects,
            long legacyTextCacheRequests,
            long legacyTextCacheMisses,
            long legacyTextSameRawFastPaths) {

        public double seconds() {
            return elapsedNanos / 1_000_000_000.0D;
        }

        /** Wall-clock completed server ticks per second over this exact sample window. */
        public double observedTps() {
            double seconds = seconds();
            return seconds <= 0.0D ? 0.0D : (tickSamples + droppedTickSamples) / seconds;
        }

        /** Sparrow outer packets are exact; Bukkit operations remain separately reported. */
        public long knownVirtualPackets() {
            return virtualSpawnBundles + virtualMotionBundles + virtualTeleportBundles
                    + virtualRemovePackets + virtualPickupPackets;
        }

        public long legacyTextCacheHits() {
            return Math.max(0L, legacyTextCacheRequests - legacyTextCacheMisses);
        }

        public double legacyTextCacheHitRate() {
            return legacyTextCacheRequests == 0L
                    ? 0.0D
                    : (double) legacyTextCacheHits() / (double) legacyTextCacheRequests;
        }

        public String summary() {
            return String.format(Locale.ROOT,
                    "label=%s modes=%s/%s/%s/%s/%s textCache=%s/%.1f%% samples=%d tps=%.3f p50/p95/p99=%.3f/%.3f/%.3fms virtualPackets=%d anchors=%d/%d/%d",
                    label, staticAnchorDuringAnimation, packetOnlyStatic, hideIfViewObstructed,
                    visibilityRateLimit,
                    eventDrivenBlockUpdates, legacyTextComponentCache, legacyTextCacheHitRate() * 100.0D,
                    tickSamples, observedTps(), msptP50, msptP95, msptP99, knownVirtualPackets(),
                    bukkitEntitySpawns, bukkitEntityTeleports, bukkitEntityRemoves);
        }

        public String json() {
            return String.format(Locale.ROOT,
                    "{\"label\":\"%s\",\"staticAnchorDuringAnimation\":%b," +
                            "\"packetOnlyStatic\":%b,\"hideIfViewObstructed\":%b," +
                            "\"visibilityRateLimit\":%b," +
                            "\"visibilityBucketSize\":%d,\"visibilityRestorePerTick\":%d," +
                            "\"droppedLabelVisibilityCulling\":%b,\"droppedLabelViewDistance\":%d," +
                            "\"droppedLabelVisibilityRateLimit\":%b," +
                            "\"droppedLabelVisibilityBucketSize\":%d," +
                            "\"droppedLabelVisibilityRestorePerTick\":%d," +
                            "\"eventDrivenBlockUpdates\":%b,\"blockUpdateMaxDirtyPerTick\":%d," +
                            "\"legacyTextComponentCache\":%b," +
                            "\"seconds\":%.3f,\"tickSamples\":%d,\"observedTps\":%.6f," +
                            "\"droppedTickSamples\":%d,\"msptP50\":%.6f,\"msptP95\":%.6f," +
                            "\"msptP99\":%.6f,\"msptP999\":%.6f,\"msptMax\":%.6f," +
                            "\"msptMaxBukkitTick\":%d,\"msptMaxEndEpochMillis\":%d," +
                            "\"msptMaxBlockUpdateChecks\":%d," +
                            "\"msptMaxBlockUpdateMs\":%.6f," +
                            "\"msptMean\":%.6f,\"ticksOver50ms\":%d," +
                            "\"virtualSpawnBundles\":%d,\"virtualMotionBundles\":%d," +
                            "\"virtualTeleportBundles\":%d,\"virtualRemovePackets\":%d," +
                            "\"virtualPickupPackets\":%d,\"knownVirtualPackets\":%d," +
                            "\"bukkitEntitySpawns\":%d,\"bukkitEntityRemoves\":%d," +
                            "\"bukkitEntityTeleports\":%d,\"bukkitShowCalls\":%d," +
                            "\"bukkitHideCalls\":%d,\"displaySyncs\":%d,\"itemSyncs\":%d," +
                            "\"packetOnlyItemSyncs\":%d,\"virtualViewerChecks\":%d," +
                            "\"visibilityShowsQueued\":%d,\"visibilityShowsDrained\":%d," +
                            "\"viewerFullReconciles\":%d,\"viewerCandidates\":%d," +
                            "\"craftEngineCullingCandidates\":%d," +
                            "\"craftEngineCullingShowDecisions\":%d," +
                            "\"craftEngineCullingHideDecisions\":%d," +
                            "\"craftEngineCullingRetainedRegistrations\":%d," +
                            "\"itemAnimationMs\":%.6f,\"droppedItemMs\":%.6f," +
                            "\"blockUpdateChecks\":%d,\"blockUpdateMs\":%.6f," +
                            "\"blockUpdateCoordinatorLanesMax\":%d," +
                            "\"blockUpdateDirtyQueueMax\":%d," +
                            "\"blockUpdateActiveQueueMax\":%d," +
                            "\"preferenceIoOperations\":%d," +
                            "\"preferenceIoFailures\":%d," +
                            "\"preferenceIoQueueDepthMax\":%d," +
                            "\"preferenceSqlStatements\":%d," +
                            "\"preferenceDatabaseReconnects\":%d," +
                            "\"legacyTextCacheRequests\":%d,\"legacyTextCacheMisses\":%d," +
                            "\"legacyTextCacheHits\":%d,\"legacyTextCacheHitRate\":%.6f," +
                            "\"legacyTextSameRawFastPaths\":%d}",
                    label, staticAnchorDuringAnimation, packetOnlyStatic, hideIfViewObstructed,
                    visibilityRateLimit,
                    visibilityBucketSize, visibilityRestorePerTick,
                    droppedLabelVisibility.cullingEnabled(), droppedLabelVisibility.viewDistance(),
                    droppedLabelVisibility.rateLimitEnabled(), droppedLabelVisibility.bucketSize(),
                    droppedLabelVisibility.restorePerTick(), eventDrivenBlockUpdates,
                    blockUpdateMaxDirtyPerTick, legacyTextComponentCache,
                    seconds(), tickSamples, observedTps(), droppedTickSamples,
                    msptP50, msptP95, msptP99,
                    msptP999, msptMax, msptMaxBukkitTick, msptMaxEndEpochMillis, msptMaxBlockUpdateChecks,
                    msptMaxBlockUpdateNanos / 1_000_000.0D, msptMean, ticksOver50ms, virtualSpawnBundles,
                    virtualMotionBundles, virtualTeleportBundles, virtualRemovePackets, virtualPickupPackets,
                    knownVirtualPackets(), bukkitEntitySpawns, bukkitEntityRemoves, bukkitEntityTeleports,
                    bukkitShowCalls, bukkitHideCalls, displaySyncs, itemSyncs, packetOnlyItemSyncs,
                    virtualViewerChecks, visibilityShowsQueued, visibilityShowsDrained,
                    viewerFullReconciles, viewerCandidates, craftEngineCullingCandidates,
                    craftEngineCullingShowDecisions, craftEngineCullingHideDecisions,
                    craftEngineCullingRetainedRegistrations,
                    itemAnimationNanos / 1_000_000.0D, droppedItemNanos / 1_000_000.0D,
                    blockUpdateChecks, blockUpdateNanos / 1_000_000.0D,
                    blockUpdateCoordinatorLanesMax, blockUpdateDirtyQueueMax, blockUpdateActiveQueueMax,
                    preferenceIoOperations, preferenceIoFailures, preferenceIoQueueDepthMax,
                    preferenceSqlStatements, preferenceDatabaseReconnects,
                    legacyTextCacheRequests, legacyTextCacheMisses, legacyTextCacheHits(),
                    legacyTextCacheHitRate(), legacyTextSameRawFastPaths);
        }
    }

    /**
     * Constant-space attribution for block-update work performed during the
     * slowest completed tick. This tracker is main-thread confined by the
     * Paper runtime; package visibility keeps its rollover semantics directly
     * unit-testable without starting a server.
     */
    static final class SlowestTickTracker {

        private long currentBlockUpdateChecks;
        private long currentBlockUpdateNanos;
        private double slowestDuration;
        private int slowestBukkitTick;
        private long slowestEndEpochMillis;
        private long slowestBlockUpdateChecks;
        private long slowestBlockUpdateNanos;

        SlowestTickTracker() {
            reset();
        }

        void reset() {
            currentBlockUpdateChecks = 0;
            currentBlockUpdateNanos = 0;
            slowestDuration = -1.0D;
            slowestBukkitTick = -1;
            slowestEndEpochMillis = -1L;
            slowestBlockUpdateChecks = 0;
            slowestBlockUpdateNanos = 0;
        }

        void blockUpdateChecks(int checks, long nanos) {
            currentBlockUpdateChecks += checks;
            currentBlockUpdateNanos += nanos;
        }

        void completeTick(int bukkitTick, double duration) {
            long endEpochMillis = duration > slowestDuration ? System.currentTimeMillis() : -1L;
            completeTick(bukkitTick, duration, endEpochMillis);
        }

        void completeTick(int bukkitTick, double duration, long endEpochMillis) {
            if (duration > slowestDuration) {
                slowestDuration = duration;
                slowestBukkitTick = bukkitTick;
                slowestEndEpochMillis = endEpochMillis;
                slowestBlockUpdateChecks = currentBlockUpdateChecks;
                slowestBlockUpdateNanos = currentBlockUpdateNanos;
            }
            discardTick();
        }

        void discardTick() {
            currentBlockUpdateChecks = 0;
            currentBlockUpdateNanos = 0;
        }

        int slowestBukkitTick() {
            return slowestBukkitTick;
        }

        long slowestEndEpochMillis() {
            return slowestEndEpochMillis;
        }

        long slowestBlockUpdateChecks() {
            return slowestBlockUpdateChecks;
        }

        long slowestBlockUpdateNanos() {
            return slowestBlockUpdateNanos;
        }
    }
}
