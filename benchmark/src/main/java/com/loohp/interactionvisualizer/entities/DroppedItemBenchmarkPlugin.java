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

package com.loohp.interactionvisualizer.entities;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Disposable benchmark plugin run only by the GitHub Actions performance job.
 * It deliberately alternates A/B order and reports medians and p95 values.
 */
public final class DroppedItemBenchmarkPlugin extends JavaPlugin {

    private static final int WARMUP_ROUNDS = 8;
    private static final int MEASUREMENT_ROUNDS = 40;
    private static final int VISIBILITY_SEEDS = 3;
    private static final long SAMPLE_TARGET_NANOS = 2_000_000L;
    private static final int MAX_SAMPLE_REPETITIONS = 4_096;
    private static final double VIEW_DISTANCE = 72.0D;
    private static final UUID BENCHMARK_WORLD_ID = UUID.fromString("f17968b7-ad29-4e08-bc29-56dc57f74b90");
    private static volatile long blackhole;

    private final List<String> results = new ArrayList<>();

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                runBenchmarks();
            } catch (Throwable throwable) {
                getLogger().severe("A/B benchmark failed: " + throwable.getMessage());
                throwable.printStackTrace();
            } finally {
                Bukkit.shutdown();
            }
        });
    }

    private void runBenchmarks() throws IOException {
        World world = Bukkit.getWorlds().getFirst();
        world.setAutoSave(false);
        world.getEntitiesByClass(Item.class).forEach(Item::remove);

        for (int itemCount : List.of(250, 1000, 2500)) {
            benchmarkCramping(world, itemCount, false);
            benchmarkCramping(world, itemCount, true);
        }
        for (String distribution : List.of("uniform", "hotspot", "no-hit")) {
            for (int itemCount : List.of(500, 2000, 8000)) {
                for (int viewerCount : List.of(1, 8, 32, 64, 96, 128, 192, 256, 384, 512, 768, 1024)) {
                    for (int seed = 0; seed < VISIBILITY_SEEDS; seed++) {
                        benchmarkVisibility(itemCount, viewerCount, distribution, seed);
                    }
                }
            }
        }
        for (int pending : List.of(100, 500, 2000, 8000)) {
            reportTokenBucket(pending, 128, 32);
        }

        Files.createDirectories(getDataFolder().toPath());
        Path output = getDataFolder().toPath().resolve("benchmark-results.jsonl");
        Files.write(output, results);
        getLogger().info("Wrote " + results.size() + " A/B results to " + output.toAbsolutePath());
    }

    private void benchmarkCramping(World world, int itemCount, boolean clustered) {
        List<Item> items = spawnItems(world, itemCount, clustered);
        UUID worldId = world.getUID();
        LongSupplier baseline = () -> paperNearbyQueries(items);
        LongSupplier candidate = () -> indexedNearbyQueries(items, worldId);
        Comparison comparison = compare(baseline, candidate);
        String result = String.format(Locale.ROOT,
                "{\"benchmark\":\"cramping\",\"distribution\":\"%s\",\"items\":%d," +
                        "\"baselineMedianNs\":%d,\"baselineP95Ns\":%d," +
                        "\"candidateMedianNs\":%d,\"candidateP95Ns\":%d,\"speedup\":%.3f}",
                clustered ? "clustered" : "sparse", itemCount,
                comparison.baseline().median(), comparison.baseline().p95(),
                comparison.candidate().median(), comparison.candidate().p95(), comparison.speedup());
        report(result);
        items.forEach(Item::remove);
    }

    private List<Item> spawnItems(World world, int itemCount, boolean clustered) {
        int logicalCount = clustered ? (itemCount + 7) / 8 : itemCount;
        int columns = (int) Math.ceil(Math.sqrt(logicalCount));
        double spacing = clustered ? 2.0D : 1.25D;
        List<Item> items = new ArrayList<>(itemCount);
        for (int index = 0; index < itemCount; index++) {
            int logicalIndex = clustered ? index / 8 : index;
            double baseX = 8.0D + (logicalIndex % columns) * spacing;
            double baseZ = 8.0D + (logicalIndex / columns) * spacing;
            int clusterIndex = clustered ? index % 8 : 0;
            double x = baseX + (clusterIndex & 1) * 0.12D;
            double y = 80.0D + ((clusterIndex >>> 1) & 1) * 0.12D;
            double z = baseZ + ((clusterIndex >>> 2) & 1) * 0.12D;
            world.getChunkAt((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4).load();
            ItemStack stack = new ItemStack(Material.STONE);
            int itemId = index;
            stack.editMeta(meta -> meta.customName(Component.text("iv-benchmark-" + itemId)));
            Item item = world.dropItem(new Location(world, x, y, z), stack, entity -> {
                entity.setGravity(false);
                entity.setVelocity(new Vector());
                entity.setPickupDelay(Integer.MAX_VALUE);
                entity.setWillAge(false);
            });
            items.add(item);
        }
        return items;
    }

    private static long paperNearbyQueries(List<Item> items) {
        long cramped = 0;
        for (Item item : items) {
            if (item.getWorld().getNearbyEntitiesByType(Item.class, item.getLocation(), 0.5D, 0.5D, 0.5D)
                    .stream().limit(7L).count() > 6L) {
                cramped++;
            }
        }
        blackhole = cramped;
        return cramped;
    }

    private static long indexedNearbyQueries(List<Item> items, UUID worldId) {
        DroppedItemSpatialIndex index = new DroppedItemSpatialIndex();
        List<Location> locations = new ArrayList<>(items.size());
        for (Item item : items) {
            Location location = item.getLocation();
            locations.add(location);
            index.addItem(worldId, location.getX(), location.getY(), location.getZ());
        }
        long cramped = 0;
        for (Location location : locations) {
            if (index.exceedsItemLimit(worldId, location.getX(), location.getY(), location.getZ(), 6)) {
                cramped++;
            }
        }
        blackhole = cramped;
        return cramped;
    }

    private void benchmarkVisibility(int itemCount, int viewerCount, String distribution, int seed) {
        Random random = new Random(0x51A71A1L + itemCount * 31L + viewerCount * 17L
                + distribution.hashCode() * 13L + seed * 0x9E3779B9L);
        List<Point> items;
        List<Point> viewers;
        switch (distribution) {
            case "uniform" -> {
                items = randomPoints(random, itemCount, 0.0D, 1024.0D);
                viewers = randomPoints(random, viewerCount, 0.0D, 1024.0D);
            }
            case "hotspot" -> {
                items = randomPoints(random, itemCount, 448.0D, 128.0D);
                viewers = randomPoints(random, viewerCount, 448.0D, 128.0D);
            }
            case "no-hit" -> {
                items = randomPoints(random, itemCount, 0.0D, 256.0D);
                viewers = randomPoints(random, viewerCount, 768.0D, 256.0D);
            }
            default -> throw new IllegalArgumentException("Unknown visibility distribution: " + distribution);
        }
        LongSupplier baseline = () -> linearActiveLabels(items, viewers, VIEW_DISTANCE);
        LongSupplier candidate = () -> indexedActiveLabels(items, viewers, VIEW_DISTANCE);
        long expectedLabels = baseline.getAsLong();
        long candidateLabels = candidate.getAsLong();
        if (expectedLabels != candidateLabels) {
            throw new IllegalStateException("Visibility A/B mismatch: " + expectedLabels + " != " + candidateLabels);
        }
        Comparison comparison = compare(baseline, candidate);
        double reduction = itemCount == 0 ? 0.0D : 100.0D * (itemCount - expectedLabels) / itemCount;
        String result = String.format(Locale.ROOT,
                "{\"benchmark\":\"visibility\",\"distribution\":\"%s\",\"seed\":%d," +
                        "\"items\":%d,\"viewers\":%d," +
                        "\"range\":%.1f,\"baseline\":\"linear-viewer-snapshot-any\"," +
                        "\"candidate\":\"production-primitive-viewer-index\",\"indexRebuiltPerOperation\":true," +
                        "\"candidateUsesGrid\":false,\"sampleTargetNs\":%d,\"measurementRounds\":%d," +
                        "\"baselineMedianNs\":%d,\"baselineP95Ns\":%d," +
                        "\"candidateMedianNs\":%d,\"candidateP95Ns\":%d,\"speedup\":%.3f," +
                        "\"baselineRoundsNs\":%s,\"candidateRoundsNs\":%s," +
                        "\"allLabels\":%d,\"activeLabels\":%d," +
                        "\"labelReductionPct\":%.3f}",
                distribution, seed, itemCount, viewerCount, VIEW_DISTANCE, SAMPLE_TARGET_NANOS, MEASUREMENT_ROUNDS,
                comparison.baseline().median(), comparison.baseline().p95(),
                comparison.candidate().median(), comparison.candidate().p95(), comparison.speedup(),
                Arrays.toString(comparison.baselineRoundsNs()), Arrays.toString(comparison.candidateRoundsNs()),
                itemCount, expectedLabels, reduction);
        report(result);
    }

    private static List<Point> randomPoints(Random random, int count, double offset, double width) {
        List<Point> points = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            points.add(new Point(index, offset + random.nextDouble(width), 64.0D + random.nextDouble(32.0D),
                    offset + random.nextDouble(width)));
        }
        return points;
    }

    private static long linearActiveLabels(List<Point> items, List<Point> viewers, double range) {
        List<Point> viewerSnapshot = new ArrayList<>(viewers.size());
        for (Point viewer : viewers) {
            viewerSnapshot.add(new Point(viewer.id(), viewer.x(), viewer.y(), viewer.z()));
        }
        long active = 0;
        double rangeSquared = range * range;
        for (Point item : items) {
            for (Point viewer : viewerSnapshot) {
                if (item.distanceSquared(viewer) <= rangeSquared) {
                    active++;
                    break;
                }
            }
        }
        blackhole = active;
        return active;
    }

    private static long indexedActiveLabels(List<Point> items, List<Point> viewers, double range) {
        DroppedItemSpatialIndex.ViewerIndex index = createViewerIndex(viewers);
        long active = 0;
        for (Point item : items) {
            if (index.hasViewerWithin(BENCHMARK_WORLD_ID, item.x(), item.y(), item.z(), range)) {
                active++;
            }
        }
        blackhole = active;
        return active;
    }

    private static DroppedItemSpatialIndex.ViewerIndex createViewerIndex(List<Point> viewers) {
        DroppedItemSpatialIndex.ViewerIndex index = new DroppedItemSpatialIndex.ViewerIndex(viewers.size());
        for (Point viewer : viewers) {
            index.addViewer(BENCHMARK_WORLD_ID, viewer.x(), viewer.y(), viewer.z());
        }
        return index;
    }

    private void reportTokenBucket(int pending, int capacity, int refill) {
        int firstTick = Math.min(pending, capacity);
        int remaining = Math.max(0, pending - firstTick);
        int convergenceTicks = pending == 0 ? 0 : 1 + (remaining + refill - 1) / refill;
        double peakReduction = pending == 0 ? 0.0D : 100.0D * (pending - firstTick) / pending;
        report(String.format(Locale.ROOT,
                "{\"benchmark\":\"visibility-burst\",\"pending\":%d," +
                        "\"baselinePeakOpsPerTick\":%d,\"candidatePeakOpsPerTick\":%d," +
                        "\"peakReductionPct\":%.3f,\"convergenceTicks\":%d}",
                pending, pending, firstTick, peakReduction, convergenceTicks));
    }

    private static Comparison compare(LongSupplier baseline, LongSupplier candidate) {
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            if ((round & 1) == 0) {
                time(baseline);
                time(candidate);
            } else {
                time(candidate);
                time(baseline);
            }
        }
        long[] baselineTimes = new long[MEASUREMENT_ROUNDS];
        long[] candidateTimes = new long[MEASUREMENT_ROUNDS];
        for (int round = 0; round < MEASUREMENT_ROUNDS; round++) {
            if ((round & 1) == 0) {
                baselineTimes[round] = time(baseline);
                candidateTimes[round] = time(candidate);
            } else {
                candidateTimes[round] = time(candidate);
                baselineTimes[round] = time(baseline);
            }
        }
        Samples baselineSamples = Samples.of(baselineTimes);
        Samples candidateSamples = Samples.of(candidateTimes);
        return new Comparison(baselineSamples, candidateSamples, baselineTimes, candidateTimes,
                (double) baselineSamples.median() / Math.max(1L, candidateSamples.median()));
    }

    private static long time(LongSupplier operation) {
        long started = System.nanoTime();
        int repetitions = 0;
        long elapsed;
        do {
            operation.getAsLong();
            repetitions++;
            elapsed = System.nanoTime() - started;
        } while (elapsed < SAMPLE_TARGET_NANOS && repetitions < MAX_SAMPLE_REPETITIONS);
        return elapsed / repetitions;
    }

    private void report(String result) {
        results.add(result);
        getLogger().info("IV_BENCH_RESULT " + result);
    }

    private record Samples(long median, long p95) {

        private static Samples of(long[] values) {
            long[] sorted = Arrays.copyOf(values, values.length);
            Arrays.sort(sorted);
            return new Samples(sorted[sorted.length / 2], sorted[(int) Math.ceil(sorted.length * 0.95D) - 1]);
        }
    }

    private record Comparison(Samples baseline, Samples candidate, long[] baselineRoundsNs,
                              long[] candidateRoundsNs, double speedup) {
    }

    private record Point(int id, double x, double y, double z) {

        private double distanceSquared(Point other) {
            double deltaX = x - other.x;
            double deltaY = y - other.y;
            double deltaZ = z - other.z;
            return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        }
    }

}
