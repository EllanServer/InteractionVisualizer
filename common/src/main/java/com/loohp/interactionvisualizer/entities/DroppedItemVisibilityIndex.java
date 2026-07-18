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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A rebuildable spatial index for exact per-viewer dropped-label queries.
 *
 * <p>The table uses primitive packed X/Z cell keys. Y remains on each point
 * and participates in the final squared-distance check, preserving the old
 * three-dimensional visibility result without allocating lookup keys.</p>
 */
final class DroppedItemVisibilityIndex<T> {

    private static final double CELL_SIZE = 16.0D;

    private final Map<UUID, WorldGrid<T>> worlds = new HashMap<>();

    void add(UUID worldId, double x, double y, double z, T value) {
        worlds.computeIfAbsent(worldId, ignored -> new WorldGrid<>())
                .add(x, y, z, value);
    }

    /**
     * Adds every exact match to {@code output} and returns the number of points
     * inspected after the spatial lookup. The count is exposed for IV_PERF.
     */
    int queryInto(UUID worldId, double x, double y, double z, double range,
                  Collection<? super T> output) {
        WorldGrid<T> world = worlds.get(worldId);
        if (world == null || !Double.isFinite(range) || range < 0.0D) {
            return 0;
        }
        return world.queryInto(x, y, z, range, output);
    }

    private static int coordinate(double value) {
        return (int) Math.floor(value / CELL_SIZE);
    }

    private static long cellKey(int x, int z) {
        return (long) x << 32 | z & 0xFFFF_FFFFL;
    }

    private static final class WorldGrid<T> {

        private static final int INITIAL_CAPACITY = 16;
        private static final float LOAD_FACTOR = 0.65F;

        private long[] keys = new long[INITIAL_CAPACITY];
        private Object[] buckets = new Object[INITIAL_CAPACITY];
        private boolean[] occupied = new boolean[INITIAL_CAPACITY];
        private int mask = INITIAL_CAPACITY - 1;
        private int resizeAt = (int) (INITIAL_CAPACITY * LOAD_FACTOR);
        private int size;

        private void add(double x, double y, double z, T value) {
            long key = cellKey(coordinate(x), coordinate(z));
            int slot = findSlot(key);
            if (!occupied[slot]) {
                if (size + 1 > resizeAt) {
                    resize();
                    slot = findSlot(key);
                }
                occupied[slot] = true;
                keys[slot] = key;
                buckets[slot] = new ArrayList<Point<T>>();
                size++;
            }
            bucket(slot).add(new Point<>(x, y, z, value));
        }

        private int queryInto(double x, double y, double z, double range,
                              Collection<? super T> output) {
            int minimumX = coordinate(x - range);
            int maximumX = coordinate(x + range);
            int minimumZ = coordinate(z - range);
            int maximumZ = coordinate(z + range);
            double rangeSquared = range * range;
            int inspected = 0;
            for (int cellX = minimumX; cellX <= maximumX; cellX++) {
                for (int cellZ = minimumZ; cellZ <= maximumZ; cellZ++) {
                    int slot = findSlot(cellKey(cellX, cellZ));
                    if (!occupied[slot]) {
                        continue;
                    }
                    for (Point<T> point : bucket(slot)) {
                        inspected++;
                        double deltaX = point.x - x;
                        double deltaY = point.y - y;
                        double deltaZ = point.z - z;
                        if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= rangeSquared) {
                            output.add(point.value);
                        }
                    }
                }
            }
            return inspected;
        }

        private int findSlot(long key) {
            int slot = mix(key) & mask;
            while (occupied[slot] && keys[slot] != key) {
                slot = slot + 1 & mask;
            }
            return slot;
        }

        @SuppressWarnings("unchecked")
        private List<Point<T>> bucket(int slot) {
            return (List<Point<T>>) buckets[slot];
        }

        private void resize() {
            long[] previousKeys = keys;
            Object[] previousBuckets = buckets;
            boolean[] previousOccupied = occupied;
            int capacity = previousKeys.length << 1;
            keys = new long[capacity];
            buckets = new Object[capacity];
            occupied = new boolean[capacity];
            mask = capacity - 1;
            resizeAt = (int) (capacity * LOAD_FACTOR);
            size = 0;
            for (int index = 0; index < previousKeys.length; index++) {
                if (!previousOccupied[index]) {
                    continue;
                }
                int slot = findSlot(previousKeys[index]);
                occupied[slot] = true;
                keys[slot] = previousKeys[index];
                buckets[slot] = previousBuckets[index];
                size++;
            }
        }

        private static int mix(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdl;
            value ^= value >>> 33;
            value *= 0xc4ceb9fe1a85ec53l;
            value ^= value >>> 33;
            return (int) value;
        }
    }

    private static final class Point<T> {

        private final double x;
        private final double y;
        private final double z;
        private final T value;

        private Point(double x, double y, double z, T value) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.value = value;
        }
    }
}
