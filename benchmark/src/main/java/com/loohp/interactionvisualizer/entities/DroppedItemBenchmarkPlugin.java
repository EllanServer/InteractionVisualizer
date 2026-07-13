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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Disposable benchmark plugin run only by the GitHub Actions performance job.
 * It deliberately alternates A/B order and reports medians and p95 values.
 */
public final class DroppedItemBenchmarkPlugin extends JavaPlugin {

    private static final int WARMUP_ROUNDS = 12;
    private static final int MEASUREMENT_ROUNDS = 42;
    private static final int VISIBILITY_SEEDS = 3;
    private static final long SAMPLE_TARGET_NANOS = 2_000_000L;
    private static final int MAX_SAMPLE_REPETITIONS = 4_096;
    private static final double VIEW_DISTANCE = 72.0D;
    private static final UUID BENCHMARK_WORLD_ID = UUID.fromString("f17968b7-ad29-4e08-bc29-56dc57f74b90");
    private static volatile long blackhole;

    private final List<String> results = new ArrayList<>();
    private Path resultsOutput;
    private Path completionOutput;

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
        Files.createDirectories(getDataFolder().toPath());
        resultsOutput = getDataFolder().toPath().resolve("benchmark-results.jsonl");
        completionOutput = getDataFolder().toPath().resolve("benchmark-complete.txt");
        Files.writeString(resultsOutput, "");
        Files.deleteIfExists(completionOutput);

        for (int itemCount : List.of(250, 1000, 2500)) {
            benchmarkCramping(world, itemCount, false);
            benchmarkCramping(world, itemCount, true);
        }
        for (String distribution : List.of("uniform", "hotspot", "no-hit", "enclosed-no-hit",
                "diagonal-no-hit", "late-hit")) {
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

        Files.writeString(completionOutput, Integer.toString(results.size()));
        getLogger().info("Wrote " + results.size() + " A/B results to " + resultsOutput.toAbsolutePath());
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
            case "enclosed-no-hit" -> {
                items = randomPoints(random, itemCount, 448.0D, 128.0D);
                viewers = enclosingRingPoints(random, viewerCount);
            }
            case "diagonal-no-hit" -> {
                items = centralPoints(random, itemCount, 4.0D);
                viewers = diagonalMissPoints(random, viewerCount);
            }
            case "late-hit" -> {
                items = centralPoints(random, itemCount, 4.0D);
                viewers = lateHitPoints(random, viewerCount);
            }
            default -> throw new IllegalArgumentException("Unknown visibility distribution: " + distribution);
        }
        LongSupplier linearReference = () -> linearActiveLabels(items, viewers, VIEW_DISTANCE);
        LongSupplier legacyProduction = () -> legacyGridActiveLabels(items, viewers, VIEW_DISTANCE);
        LongSupplier primitiveControl = () -> primitiveControlActiveLabels(items, viewers, VIEW_DISTANCE);
        LongSupplier candidate = () -> indexedActiveLabels(items, viewers, VIEW_DISTANCE);
        long expectedLabels = linearReference.getAsLong();
        long legacyLabels = legacyProduction.getAsLong();
        long controlLabels = primitiveControl.getAsLong();
        long candidateLabels = candidate.getAsLong();
        if (expectedLabels != legacyLabels || expectedLabels != controlLabels || expectedLabels != candidateLabels) {
            throw new IllegalStateException("Visibility A/B mismatch: linear=" + expectedLabels
                    + ", legacy=" + legacyLabels + ", control=" + controlLabels
                    + ", candidate=" + candidateLabels);
        }
        if ((distribution.endsWith("no-hit") && expectedLabels != 0L)
                || (distribution.equals("late-hit") && expectedLabels != itemCount)) {
            throw new IllegalStateException("Invalid " + distribution + " fixture: active=" + expectedLabels);
        }
        int startingPermutation = Math.floorMod(itemCount * 31 + viewerCount * 17 + seed, 6);
        ThreeWayComparison comparisons = compareThree(
                legacyProduction, primitiveControl, candidate, startingPermutation);
        AdaptivePathProbe pathProbe = probeAdaptivePaths(items, viewers, VIEW_DISTANCE);
        if (pathProbe.activeLabels() != expectedLabels) {
            throw new IllegalStateException("Adaptive path probe mismatch: expected=" + expectedLabels
                    + ", probe=" + pathProbe.activeLabels());
        }
        double reduction = itemCount == 0 ? 0.0D : 100.0D * (itemCount - expectedLabels) / itemCount;
        reportVisibilityComparison("visibility-production-ab", "legacy-production-viewer-grid", true,
                distribution, seed, itemCount, viewerCount, comparisons.legacyCandidate(),
                pathProbe, expectedLabels, reduction);
        reportVisibilityComparison("visibility-adaptive-control-ab", "pure-primitive-soa-control", false,
                distribution, seed, itemCount, viewerCount, comparisons.controlCandidate(),
                pathProbe, expectedLabels, reduction);
    }

    private void reportVisibilityComparison(String benchmark, String baseline, boolean baselineUsesGrid,
                                            String distribution, int seed, int itemCount, int viewerCount,
                                            Comparison comparison, AdaptivePathProbe pathProbe,
                                            long activeLabels, double reduction) {
        String result = String.format(Locale.ROOT,
                "{\"benchmark\":\"%s\",\"distribution\":\"%s\",\"seed\":%d," +
                        "\"items\":%d,\"viewers\":%d," +
                        "\"range\":%.1f,\"baseline\":\"%s\",\"baselineUsesGrid\":%b," +
                        "\"baselineUsesBounds\":false,\"sharedCandidateSample\":true," +
                        "\"candidate\":\"production-adaptive-primitive-viewer-index\"," +
                        "\"candidateStorage\":\"primitive-soa\"," +
                        "\"indexRebuiltPerOperation\":true,\"candidateUsesGrid\":true," +
                        "\"candidateUsesBounds\":true,\"candidateBuildsIndexesLazily\":true," +
                        "\"probeGridBuilt\":%b,\"probeGridActiveAtEnd\":%b," +
                        "\"probeBoundsActiveAtEnd\":%b," +
                        "\"sampleTargetNs\":%d,\"warmupRounds\":%d,\"measurementRounds\":%d," +
                        "\"roundOrder\":\"%s\"," +
                        "\"baselineMedianNs\":%d,\"baselineP95Ns\":%d," +
                        "\"candidateMedianNs\":%d,\"candidateP95Ns\":%d,\"speedup\":%.3f," +
                        "\"baselineFirstBaselineMedianNs\":%d," +
                        "\"baselineFirstCandidateMedianNs\":%d," +
                        "\"baselineFirstBaselineP95Ns\":%d," +
                        "\"baselineFirstCandidateP95Ns\":%d," +
                        "\"baselineFirstSpeedup\":%.3f," +
                        "\"candidateFirstBaselineMedianNs\":%d," +
                        "\"candidateFirstCandidateMedianNs\":%d," +
                        "\"candidateFirstBaselineP95Ns\":%d," +
                        "\"candidateFirstCandidateP95Ns\":%d," +
                        "\"candidateFirstSpeedup\":%.3f," +
                        "\"baselineRoundsNs\":%s,\"candidateRoundsNs\":%s," +
                        "\"allLabels\":%d,\"activeLabels\":%d," +
                        "\"labelReductionPct\":%.3f}",
                benchmark, distribution, seed, itemCount, viewerCount, VIEW_DISTANCE, baseline, baselineUsesGrid,
                pathProbe.gridBuilt(), pathProbe.gridActiveAtEnd(), pathProbe.boundsActiveAtEnd(),
                SAMPLE_TARGET_NANOS, WARMUP_ROUNDS, MEASUREMENT_ROUNDS, comparison.roundOrder(),
                comparison.baseline().median(), comparison.baseline().p95(),
                comparison.candidate().median(), comparison.candidate().p95(), comparison.speedup(),
                comparison.baselineFirst().baseline().median(),
                comparison.baselineFirst().candidate().median(),
                comparison.baselineFirst().baseline().p95(), comparison.baselineFirst().candidate().p95(),
                comparison.baselineFirst().speedup(),
                comparison.candidateFirst().baseline().median(),
                comparison.candidateFirst().candidate().median(),
                comparison.candidateFirst().baseline().p95(), comparison.candidateFirst().candidate().p95(),
                comparison.candidateFirst().speedup(),
                Arrays.toString(comparison.baselineRoundsNs()), Arrays.toString(comparison.candidateRoundsNs()),
                itemCount, activeLabels, reduction);
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

    /**
     * Places viewers around an empty central region. From eight viewers onward the item region is
     * enclosed by the viewer AABB, while every viewer remains well beyond the visibility radius.
     * This prevents an outer-bounds fast rejection from disguising worst-case miss scans.
     */
    private static List<Point> enclosingRingPoints(Random random, int count) {
        List<Point> points = new ArrayList<>(count);
        double phase = random.nextDouble(2.0D * Math.PI);
        for (int index = 0; index < count; index++) {
            double angle = phase + 2.0D * Math.PI * index / Math.max(1, count);
            points.add(new Point(index,
                    512.0D + Math.cos(angle) * 300.0D,
                    64.0D + random.nextDouble(32.0D),
                    512.0D + Math.sin(angle) * 300.0D));
        }
        return points;
    }

    private static List<Point> centralPoints(Random random, int count, double radius) {
        List<Point> points = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            points.add(new Point(index,
                    512.0D + random.nextDouble(-radius, radius),
                    80.0D,
                    512.0D + random.nextDouble(-radius, radius)));
        }
        return points;
    }

    private static List<Point> diagonalMissPoints(Random random, int count) {
        List<Point> points = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int quadrant = index & 3;
            double xSign = (quadrant & 1) == 0 ? -1.0D : 1.0D;
            double zSign = (quadrant & 2) == 0 ? -1.0D : 1.0D;
            points.add(new Point(index,
                    512.0D + xSign * random.nextDouble(60.0D, 64.0D),
                    80.0D,
                    512.0D + zSign * random.nextDouble(60.0D, 64.0D)));
        }
        return points;
    }

    private static List<Point> lateHitPoints(Random random, int count) {
        if (count == 0) {
            return List.of();
        }
        List<Point> points = enclosingRingPoints(random, count - 1);
        points.add(new Point(count - 1, 512.0D, 80.0D, 512.0D));
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

    private static long legacyGridActiveLabels(List<Point> items, List<Point> viewers, double range) {
        LegacyViewerIndex index = new LegacyViewerIndex();
        for (Point viewer : viewers) {
            index.addViewer(BENCHMARK_WORLD_ID, viewer.x(), viewer.y(), viewer.z());
        }
        long active = 0;
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            Point item = items.get(itemIndex);
            if (index.hasViewerWithin(BENCHMARK_WORLD_ID, item.x(), item.y(), item.z(), range)) {
                active++;
            }
        }
        blackhole = active;
        return active;
    }

    private static long indexedActiveLabels(List<Point> items, List<Point> viewers, double range) {
        DroppedItemSpatialIndex.ViewerIndex index = createViewerIndex(viewers);
        long active = 0;
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            Point item = items.get(itemIndex);
            if (index.hasViewerWithin(BENCHMARK_WORLD_ID, item.x(), item.y(), item.z(), range,
                    items.size() - itemIndex - 1)) {
                active++;
            }
        }
        blackhole = active;
        return active;
    }

    private static long primitiveControlActiveLabels(List<Point> items, List<Point> viewers, double range) {
        PrimitiveViewerIndex index = new PrimitiveViewerIndex(viewers.size());
        for (Point viewer : viewers) {
            index.addViewer(BENCHMARK_WORLD_ID, viewer.x(), viewer.y(), viewer.z());
        }
        long active = 0;
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            Point item = items.get(itemIndex);
            if (index.hasViewerWithin(BENCHMARK_WORLD_ID, item.x(), item.y(), item.z(), range)) {
                active++;
            }
        }
        blackhole = active;
        return active;
    }

    private static AdaptivePathProbe probeAdaptivePaths(List<Point> items, List<Point> viewers, double range) {
        DroppedItemSpatialIndex.ViewerIndex index = createViewerIndex(viewers);
        long active = 0;
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            Point item = items.get(itemIndex);
            if (index.hasViewerWithin(BENCHMARK_WORLD_ID, item.x(), item.y(), item.z(), range,
                    items.size() - itemIndex - 1)) {
                active++;
            }
        }
        return new AdaptivePathProbe(active, index.hasAdaptiveGrid(BENCHMARK_WORLD_ID),
                index.isUsingAdaptiveGrid(BENCHMARK_WORLD_ID),
                index.hasActiveBounds(BENCHMARK_WORLD_ID));
    }

    private static DroppedItemSpatialIndex.ViewerIndex createViewerIndex(List<Point> viewers) {
        DroppedItemSpatialIndex.ViewerIndex index =
                new DroppedItemSpatialIndex.ViewerIndex(viewers.size());
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
        return compare(baseline, candidate, true);
    }

    private static Comparison compare(LongSupplier baseline, LongSupplier candidate, boolean baselineStarts) {
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            boolean baselineFirst = ((round & 1) == 0) == baselineStarts;
            if (baselineFirst) {
                time(baseline);
                time(candidate);
            } else {
                time(candidate);
                time(baseline);
            }
        }
        long[] baselineTimes = new long[MEASUREMENT_ROUNDS];
        long[] candidateTimes = new long[MEASUREMENT_ROUNDS];
        boolean[] baselineFirstRounds = new boolean[MEASUREMENT_ROUNDS];
        for (int round = 0; round < MEASUREMENT_ROUNDS; round++) {
            boolean baselineFirst = ((round & 1) == 0) == baselineStarts;
            baselineFirstRounds[round] = baselineFirst;
            if (baselineFirst) {
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
                OrderStratum.of(baselineTimes, candidateTimes, baselineFirstRounds, true),
                OrderStratum.of(baselineTimes, candidateTimes, baselineFirstRounds, false),
                baselineStarts ? "ABBA" : "BAAB", baselineTimes, candidateTimes,
                (double) baselineSamples.median() / Math.max(1L, candidateSamples.median()));
    }

    private static ThreeWayComparison compareThree(LongSupplier legacy, LongSupplier control,
                                                   LongSupplier candidate, int startingPermutation) {
        LongSupplier[] operations = {legacy, control, candidate};
        int[][] permutations = {
                {0, 1, 2}, {0, 2, 1}, {1, 0, 2},
                {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
        };
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            int[] order = permutations[(startingPermutation + round) % permutations.length];
            for (int operation : order) {
                time(operations[operation]);
            }
        }
        long[][] times = new long[operations.length][MEASUREMENT_ROUNDS];
        boolean[] legacyFirst = new boolean[MEASUREMENT_ROUNDS];
        boolean[] controlFirst = new boolean[MEASUREMENT_ROUNDS];
        for (int round = 0; round < MEASUREMENT_ROUNDS; round++) {
            int[] order = permutations[(startingPermutation + round) % permutations.length];
            int legacyPosition = 0;
            int controlPosition = 0;
            int candidatePosition = 0;
            for (int position = 0; position < order.length; position++) {
                int operation = order[position];
                times[operation][round] = time(operations[operation]);
                if (operation == 0) {
                    legacyPosition = position;
                } else if (operation == 1) {
                    controlPosition = position;
                } else {
                    candidatePosition = position;
                }
            }
            legacyFirst[round] = legacyPosition < candidatePosition;
            controlFirst[round] = controlPosition < candidatePosition;
        }
        String roundOrder = "BALANCED-6@" + startingPermutation;
        return new ThreeWayComparison(
                comparisonOf(times[0], times[2], legacyFirst, roundOrder),
                comparisonOf(times[1], times[2], controlFirst, roundOrder));
    }

    private static Comparison comparisonOf(long[] baselineTimes, long[] candidateTimes,
                                           boolean[] baselineFirstRounds, String roundOrder) {
        Samples baselineSamples = Samples.of(baselineTimes);
        Samples candidateSamples = Samples.of(candidateTimes);
        return new Comparison(baselineSamples, candidateSamples,
                OrderStratum.of(baselineTimes, candidateTimes, baselineFirstRounds, true),
                OrderStratum.of(baselineTimes, candidateTimes, baselineFirstRounds, false),
                roundOrder, baselineTimes, candidateTimes,
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
        try {
            Files.writeString(resultsOutput, result + System.lineSeparator(), StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to append benchmark result", exception);
        }
        getLogger().info("IV_BENCH_RESULT " + result);
    }

    private record Samples(long median, long p95) {

        private static Samples of(long[] values) {
            long[] sorted = Arrays.copyOf(values, values.length);
            Arrays.sort(sorted);
            return new Samples(sorted[sorted.length / 2], sorted[(int) Math.ceil(sorted.length * 0.95D) - 1]);
        }
    }

    private record OrderStratum(Samples baseline, Samples candidate, double speedup) {

        private static OrderStratum of(long[] baselineTimes, long[] candidateTimes,
                                       boolean[] baselineFirstRounds, boolean baselineFirst) {
            int size = 0;
            for (boolean value : baselineFirstRounds) {
                if (value == baselineFirst) {
                    size++;
                }
            }
            long[] baseline = new long[size];
            long[] candidate = new long[size];
            int output = 0;
            for (int index = 0; index < baselineFirstRounds.length; index++) {
                if (baselineFirstRounds[index] == baselineFirst) {
                    baseline[output] = baselineTimes[index];
                    candidate[output] = candidateTimes[index];
                    output++;
                }
            }
            Samples baselineSamples = Samples.of(baseline);
            Samples candidateSamples = Samples.of(candidate);
            return new OrderStratum(baselineSamples, candidateSamples,
                    (double) baselineSamples.median() / Math.max(1L, candidateSamples.median()));
        }
    }

    private record Comparison(Samples baseline, Samples candidate, OrderStratum baselineFirst,
                              OrderStratum candidateFirst, String roundOrder, long[] baselineRoundsNs,
                              long[] candidateRoundsNs, double speedup) {
    }

    private record ThreeWayComparison(Comparison legacyCandidate, Comparison controlCandidate) {
    }

    private record AdaptivePathProbe(long activeLabels, boolean gridBuilt,
                                     boolean gridActiveAtEnd, boolean boundsActiveAtEnd) {
    }

    /** Strict pure primitive SoA lower-bound control without bounds or an adaptive grid. */
    private static final class PrimitiveViewerIndex {

        private final double[] xCoordinates;
        private final double[] yCoordinates;
        private final double[] zCoordinates;
        private UUID worldId;
        private int size;

        private PrimitiveViewerIndex(int capacity) {
            xCoordinates = new double[capacity];
            yCoordinates = new double[capacity];
            zCoordinates = new double[capacity];
        }

        private void addViewer(UUID worldId, double x, double y, double z) {
            if (this.worldId == null) {
                this.worldId = worldId;
            }
            xCoordinates[size] = x;
            yCoordinates[size] = y;
            zCoordinates[size] = z;
            size++;
        }

        private boolean hasViewerWithin(UUID worldId, double x, double y, double z, double range) {
            if (!java.util.Objects.equals(this.worldId, worldId)) {
                return false;
            }
            double rangeSquared = range * range;
            for (int index = 0; index < size; index++) {
                double deltaX = xCoordinates[index] - x;
                double deltaY = yCoordinates[index] - y;
                double deltaZ = zCoordinates[index] - z;
                if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= rangeSquared) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Exact copy of the viewer-grid strategy used by production before the optimized candidate. */
    private static final class LegacyViewerIndex {

        private static final double CELL_SIZE = 16.0D;
        private final Map<LegacyViewerCell, List<LegacyViewerPoint>> viewerCells = new HashMap<>();

        private void addViewer(UUID worldId, double x, double y, double z) {
            viewerCells.computeIfAbsent(cell(worldId, x, z), ignored -> new ArrayList<>())
                    .add(new LegacyViewerPoint(x, y, z));
        }

        private boolean hasViewerWithin(UUID worldId, double x, double y, double z, double range) {
            if (range < 0.0D) {
                return false;
            }
            int minimumX = coordinate(x - range);
            int maximumX = coordinate(x + range);
            int minimumZ = coordinate(z - range);
            int maximumZ = coordinate(z + range);
            double rangeSquared = range * range;
            for (int cellX = minimumX; cellX <= maximumX; cellX++) {
                for (int cellZ = minimumZ; cellZ <= maximumZ; cellZ++) {
                    List<LegacyViewerPoint> points = viewerCells.get(new LegacyViewerCell(worldId, cellX, cellZ));
                    if (points == null) {
                        continue;
                    }
                    for (LegacyViewerPoint point : points) {
                        double deltaX = point.x() - x;
                        double deltaY = point.y() - y;
                        double deltaZ = point.z() - z;
                        if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= rangeSquared) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private static LegacyViewerCell cell(UUID worldId, double x, double z) {
            return new LegacyViewerCell(worldId, coordinate(x), coordinate(z));
        }

        private static int coordinate(double value) {
            return (int) Math.floor(value / CELL_SIZE);
        }
    }

    private record LegacyViewerCell(UUID worldId, int x, int z) {
    }

    private record LegacyViewerPoint(double x, double y, double z) {
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
