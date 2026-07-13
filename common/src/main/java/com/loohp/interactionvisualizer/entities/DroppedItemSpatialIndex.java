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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-update spatial indexes for dropped items and eligible viewers. */
final class DroppedItemSpatialIndex {

    private static final double ITEM_QUERY_RADIUS = 0.5D;
    private static final double ITEM_CELL_SIZE = ITEM_QUERY_RADIUS;

    private final Map<Cell, List<Point>> itemCells = new HashMap<>();

    void addItem(UUID worldId, double x, double y, double z) {
        itemCells.computeIfAbsent(itemCell(worldId, x, y, z), ignored -> new ArrayList<>())
                .add(new Point(x, y, z));
    }

    boolean exceedsItemLimit(UUID worldId, double x, double y, double z, int maximum) {
        if (maximum < 1) {
            return false;
        }

        int minimumX = itemCoordinate(x - ITEM_QUERY_RADIUS);
        int maximumX = itemCoordinate(x + ITEM_QUERY_RADIUS);
        int minimumY = itemCoordinate(y - ITEM_QUERY_RADIUS);
        int maximumY = itemCoordinate(y + ITEM_QUERY_RADIUS);
        int minimumZ = itemCoordinate(z - ITEM_QUERY_RADIUS);
        int maximumZ = itemCoordinate(z + ITEM_QUERY_RADIUS);
        int matches = 0;

        for (int cellX = minimumX; cellX <= maximumX; cellX++) {
            for (int cellY = minimumY; cellY <= maximumY; cellY++) {
                for (int cellZ = minimumZ; cellZ <= maximumZ; cellZ++) {
                    List<Point> points = itemCells.get(new Cell(worldId, cellX, cellY, cellZ));
                    if (points == null) {
                        continue;
                    }
                    for (Point point : points) {
                        if (Math.abs(point.x() - x) <= ITEM_QUERY_RADIUS
                                && Math.abs(point.y() - y) <= ITEM_QUERY_RADIUS
                                && Math.abs(point.z() - z) <= ITEM_QUERY_RADIUS
                                && ++matches > maximum) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static Cell itemCell(UUID worldId, double x, double y, double z) {
        return new Cell(worldId, itemCoordinate(x), itemCoordinate(y), itemCoordinate(z));
    }

    private static int itemCoordinate(double coordinate) {
        return (int) Math.floor(coordinate / ITEM_CELL_SIZE);
    }

    static final class ViewerIndex {

        private UUID singleWorldId;
        private WorldViewerBucket singleWorldViewers;
        private Map<UUID, WorldViewerBucket> viewersByWorld;
        private final int expectedViewers;

        ViewerIndex() {
            this(0);
        }

        ViewerIndex(int expectedViewers) {
            if (expectedViewers < 0) {
                throw new IllegalArgumentException("expectedViewers must be non-negative");
            }
            this.expectedViewers = expectedViewers;
        }

        void addViewer(UUID worldId, double x, double y, double z) {
            if (singleWorldViewers == null) {
                singleWorldId = worldId;
                singleWorldViewers = new WorldViewerBucket(expectedViewers);
                singleWorldViewers.add(x, y, z);
                return;
            }
            if (viewersByWorld == null && java.util.Objects.equals(singleWorldId, worldId)) {
                singleWorldViewers.add(x, y, z);
                return;
            }
            if (viewersByWorld == null) {
                viewersByWorld = new HashMap<>();
                viewersByWorld.put(singleWorldId, singleWorldViewers);
            }
            viewersByWorld.computeIfAbsent(worldId, ignored -> new WorldViewerBucket(0)).add(x, y, z);
        }

        boolean isEmpty() {
            return singleWorldViewers == null;
        }

        boolean hasViewerWithin(UUID worldId, double x, double y, double z, double range) {
            if (range < 0.0D) {
                return false;
            }
            WorldViewerBucket viewers = viewersByWorld == null
                    ? java.util.Objects.equals(singleWorldId, worldId) ? singleWorldViewers : null
                    : viewersByWorld.get(worldId);
            return viewers != null && viewers.hasViewerWithin(x, y, z, range * range);
        }

        private static final class WorldViewerBucket {

            private static final int INITIAL_CAPACITY = 8;
            private static final double[] EMPTY = new double[0];

            private double[] xCoordinates = EMPTY;
            private double[] yCoordinates = EMPTY;
            private double[] zCoordinates = EMPTY;
            private int size;

            private WorldViewerBucket(int initialCapacity) {
                if (initialCapacity > 0) {
                    xCoordinates = new double[initialCapacity];
                    yCoordinates = new double[initialCapacity];
                    zCoordinates = new double[initialCapacity];
                }
            }

            private void add(double x, double y, double z) {
                if (size == xCoordinates.length) {
                    int capacity = Math.max(INITIAL_CAPACITY, size << 1);
                    xCoordinates = grow(xCoordinates, capacity);
                    yCoordinates = grow(yCoordinates, capacity);
                    zCoordinates = grow(zCoordinates, capacity);
                }
                xCoordinates[size] = x;
                yCoordinates[size] = y;
                zCoordinates[size] = z;
                size++;
            }

            private boolean hasViewerWithin(double x, double y, double z, double rangeSquared) {
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

            private static double[] grow(double[] source, int capacity) {
                double[] expanded = new double[capacity];
                System.arraycopy(source, 0, expanded, 0, source.length);
                return expanded;
            }
        }
    }

    private record Cell(UUID worldId, int x, int y, int z) {
    }

    private record Point(double x, double y, double z) {
    }
}
