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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Disposable benchmark plugin run only by the GitHub Actions performance job.
 * It deliberately alternates A/B order and reports medians and p95 values.
 */
public final class DroppedItemBenchmarkPlugin extends JavaPlugin {

    private static final int WARMUP_ROUNDS = 6;
    private static final int MEASUREMENT_ROUNDS = 18;
    private static final double VIEW_DISTANCE = 64.0D;
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
        for (int itemCount : List.of(500, 2000, 8000)) {
            for (int viewerCount : List.of(1, 10, 50)) {
                benchmarkVisibility(itemCount, viewerCount);
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
            stack.editMeta(meta -> meta.customName(Component.text("iv-benchmark-" + index)));
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

    private void benchmarkVisibility(int itemCount, int viewerCount) {
        Random random = new Random(0x51A71A1L + itemCount * 31L + viewerCount);
        List<Point> items = randomPoints(random, itemCount, 1024.0D);
        List<Point> viewers = randomPoints(random, viewerCount, 1024.0D);
        LongSupplier baseline = () -> scanVisibility(items, viewers);
        LongSupplier candidate = () -> indexedVisibility(items, viewers);
        long expectedPairs = baseline.getAsLong();
        long candidatePairs = candidate.getAsLong();
        if (expectedPairs != candidatePairs) {
            throw new IllegalStateException("Visibility A/B mismatch: " + expectedPairs + " != " + candidatePairs);
        }
        Comparison comparison = compare(baseline, candidate);
        int activeLabels = activeLabels(items, viewers);
        double reduction = itemCount == 0 ? 0.0D : 100.0D * (itemCount - activeLabels) / itemCount;
        String result = String.format(Locale.ROOT,
                "{\"benchmark\":\"visibility\",\"items\":%d,\"viewers\":%d," +
                        "\"baselineMedianNs\":%d,\"baselineP95Ns\":%d," +
                        "\"candidateMedianNs\":%d,\"candidateP95Ns\":%d,\"speedup\":%.3f," +
                        "\"visiblePairs\":%d,\"allLabels\":%d,\"activeLabels\":%d," +
                        "\"labelReductionPct\":%.3f}",
                itemCount, viewerCount,
                comparison.baseline().median(), comparison.baseline().p95(),
                comparison.candidate().median(), comparison.candidate().p95(), comparison.speedup(),
                expectedPairs, itemCount, activeLabels, reduction);
        report(result);
    }

    private static List<Point> randomPoints(Random random, int count, double width) {
        List<Point> points = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            points.add(new Point(index, random.nextDouble(width), 64.0D + random.nextDouble(32.0D),
                    random.nextDouble(width)));
        }
        return points;
    }

    private static long scanVisibility(List<Point> items, List<Point> viewers) {
        long pairs = 0;
        double rangeSquared = VIEW_DISTANCE * VIEW_DISTANCE;
        for (Point viewer : viewers) {
            Set<Integer> desired = new HashSet<>();
            for (Point item : items) {
                if (item.distanceSquared(viewer) <= rangeSquared) {
                    desired.add(item.id());
                }
            }
            pairs += desired.size();
        }
        blackhole = pairs;
        return pairs;
    }

    private static long indexedVisibility(List<Point> items, List<Point> viewers) {
        VisibilityIndex index = new VisibilityIndex();
        for (Point item : items) {
            index.add(item);
        }
        long pairs = 0;
        for (Point viewer : viewers) {
            pairs += index.nearby(viewer, VIEW_DISTANCE).size();
        }
        blackhole = pairs;
        return pairs;
    }

    private static int activeLabels(List<Point> items, List<Point> viewers) {
        int active = 0;
        double rangeSquared = VIEW_DISTANCE * VIEW_DISTANCE;
        for (Point item : items) {
            for (Point viewer : viewers) {
                if (item.distanceSquared(viewer) <= rangeSquared) {
                    active++;
                    break;
                }
            }
        }
        return active;
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
                baseline.getAsLong();
                candidate.getAsLong();
            } else {
                candidate.getAsLong();
                baseline.getAsLong();
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
        return new Comparison(baselineSamples, candidateSamples,
                (double) baselineSamples.median() / Math.max(1L, candidateSamples.median()));
    }

    private static long time(LongSupplier operation) {
        long started = System.nanoTime();
        operation.getAsLong();
        return System.nanoTime() - started;
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

    private record Comparison(Samples baseline, Samples candidate, double speedup) {
    }

    private record Point(int id, double x, double y, double z) {

        private double distanceSquared(Point other) {
            double deltaX = x - other.x;
            double deltaY = y - other.y;
            double deltaZ = z - other.z;
            return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        }
    }

    private static final class VisibilityIndex {

        private static final double CELL_SIZE = 16.0D;
        private final Map<Long, List<Point>> cells = new HashMap<>();

        private void add(Point point) {
            cells.computeIfAbsent(key(coordinate(point.x()), coordinate(point.z())), ignored -> new ArrayList<>())
                    .add(point);
        }

        private Set<Integer> nearby(Point viewer, double range) {
            int minimumX = coordinate(viewer.x() - range);
            int maximumX = coordinate(viewer.x() + range);
            int minimumZ = coordinate(viewer.z() - range);
            int maximumZ = coordinate(viewer.z() + range);
            double rangeSquared = range * range;
            Set<Integer> nearby = new HashSet<>();
            for (int cellX = minimumX; cellX <= maximumX; cellX++) {
                for (int cellZ = minimumZ; cellZ <= maximumZ; cellZ++) {
                    List<Point> points = cells.get(key(cellX, cellZ));
                    if (points == null) {
                        continue;
                    }
                    for (Point point : points) {
                        if (point.distanceSquared(viewer) <= rangeSquared) {
                            nearby.add(point.id());
                        }
                    }
                }
            }
            return nearby;
        }

        private static int coordinate(double value) {
            return (int) Math.floor(value / CELL_SIZE);
        }

        private static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        }
    }
}
