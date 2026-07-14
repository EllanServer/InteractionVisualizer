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

package com.loohp.interactionvisualizer.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Shared, bounded conversion cache for legacy display text.
 *
 * <p>Progress displays tend to move through a small set of synchronized text
 * states across hundreds of entities. Caching the immutable Adventure component
 * keeps the exact legacy and {@code [font=...]} parsing semantics while avoiding
 * the same flatten/compact work for every entity. Very large one-off strings
 * bypass the cache, and the maximum size bounds all remaining key cardinality.</p>
 */
public final class LegacyTextComponentCache {

    public static final int MAXIMUM_SIZE = 1_024;
    public static final int MAXIMUM_CACHEABLE_LENGTH = 2_048;
    public static final int ENTRY_BASE_WEIGHT = 256;
    public static final long MAXIMUM_WEIGHT = (long) MAXIMUM_SIZE * ENTRY_BASE_WEIGHT;
    public static final String DISABLE_PROPERTY = "interactionvisualizer.disableLegacyTextComponentCache";

    private static final boolean ENABLED = !Boolean.getBoolean(DISABLE_PROPERTY);
    private static final Cache<String, Component> CACHE = Caffeine.newBuilder()
            // The base weight preserves the 1,024-entry ceiling; raw length also
            // prevents a small number of style-heavy strings from owning it.
            .maximumWeight(MAXIMUM_WEIGHT)
            .weigher((String rawText, Component ignored) -> ENTRY_BASE_WEIGHT + rawText.length())
            // The cache is tiny; keep maintenance deterministic and off the JVM common pool.
            .executor(Runnable::run)
            .build();
    private static final AtomicReference<Measurement> ACTIVE_MEASUREMENT = new AtomicReference<>();
    private static volatile CacheMetrics latestMetrics = CacheMetrics.EMPTY;

    private LegacyTextComponentCache() {
    }

    public static Component parse(String rawText) {
        Objects.requireNonNull(rawText, "rawText");
        Measurement measurement = ACTIVE_MEASUREMENT.get();
        if (!ENABLED || rawText.length() > MAXIMUM_CACHEABLE_LENGTH) {
            recordCacheOutcome(measurement, false);
            return parseUncached(rawText);
        }
        Component cached = CACHE.getIfPresent(rawText);
        if (cached != null) {
            recordCacheOutcome(measurement, true);
            return cached;
        }
        recordCacheOutcome(measurement, false);
        return CACHE.get(rawText, LegacyTextComponentCache::parseUncached);
    }

    private static void recordCacheOutcome(Measurement measurement, boolean hit) {
        if (measurement == null) {
            return;
        }
        if (hit) {
            measurement.hits.increment();
        } else {
            measurement.misses.increment();
        }
    }

    private static Component parseUncached(String rawText) {
        return ComponentFont.parseFont(LegacyComponentSerializer.legacySection().deserialize(rawText));
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void recordSameRawFastPath() {
        Measurement measurement = ACTIVE_MEASUREMENT.get();
        if (measurement != null) {
            measurement.sameRawFastPaths.increment();
        }
    }

    public static void startMeasurement() {
        latestMetrics = CacheMetrics.EMPTY;
        ACTIVE_MEASUREMENT.set(new Measurement());
    }

    public static CacheMetrics stopMeasurement() {
        Measurement measurement = ACTIVE_MEASUREMENT.getAndSet(null);
        if (measurement != null) {
            latestMetrics = snapshot(measurement);
        }
        return latestMetrics;
    }

    public static CacheMetrics metrics() {
        Measurement measurement = ACTIVE_MEASUREMENT.get();
        return measurement == null ? latestMetrics : snapshot(measurement);
    }

    public static void invalidateAll() {
        CACHE.invalidateAll();
        CACHE.cleanUp();
    }

    static long estimatedSize() {
        CACHE.cleanUp();
        return CACHE.estimatedSize();
    }

    private static CacheMetrics snapshot(Measurement measurement) {
        long hits = measurement.hits.sum();
        long misses = measurement.misses.sum();
        return new CacheMetrics(hits + misses, misses, measurement.sameRawFastPaths.sum());
    }

    private static final class Measurement {

        private final LongAdder hits = new LongAdder();
        private final LongAdder misses = new LongAdder();
        private final LongAdder sameRawFastPaths = new LongAdder();
    }

    public record CacheMetrics(long requests, long misses, long sameRawFastPaths) {

        private static final CacheMetrics EMPTY = new CacheMetrics(0L, 0L, 0L);

        public long hits() {
            return Math.max(0L, requests - misses);
        }

        public double hitRate() {
            return requests == 0L ? 0.0D : (double) hits() / (double) requests;
        }
    }
}
