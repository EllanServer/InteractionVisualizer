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
        private final int expectedQueries;
        private final boolean adaptive;
        private UUID singleExpectedQueryWorldId;
        private int singleWorldExpectedQueries;
        private Map<UUID, Integer> expectedQueriesByWorld;
        private boolean usesPerWorldQueryBudgets;

        ViewerIndex() {
            this(0, 0);
        }

        ViewerIndex(int expectedViewers) {
            this(expectedViewers, 0);
        }

        ViewerIndex(int expectedViewers, int expectedQueries) {
            this(expectedViewers, expectedQueries, true);
        }

        ViewerIndex(int expectedViewers, int expectedQueries, boolean adaptive) {
            if (expectedViewers < 0 || expectedQueries < 0) {
                throw new IllegalArgumentException("expected sizes must be non-negative");
            }
            this.expectedViewers = expectedViewers;
            this.expectedQueries = expectedQueries;
            this.adaptive = adaptive;
        }

        void expectQuery(UUID worldId) {
            if (singleWorldViewers != null) {
                throw new IllegalStateException("query budgets must be declared before viewers");
            }
            usesPerWorldQueryBudgets = true;
            if (singleExpectedQueryWorldId == null) {
                singleExpectedQueryWorldId = worldId;
                singleWorldExpectedQueries = 1;
                return;
            }
            if (expectedQueriesByWorld == null
                    && java.util.Objects.equals(singleExpectedQueryWorldId, worldId)) {
                singleWorldExpectedQueries = saturatedIncrement(singleWorldExpectedQueries);
                return;
            }
            if (expectedQueriesByWorld == null) {
                expectedQueriesByWorld = new HashMap<>();
                expectedQueriesByWorld.put(singleExpectedQueryWorldId, singleWorldExpectedQueries);
            }
            expectedQueriesByWorld.merge(worldId, 1, ViewerIndex::saturatedAdd);
        }

        void addViewer(UUID worldId, double x, double y, double z) {
            if (singleWorldViewers == null) {
                singleWorldId = worldId;
                singleWorldViewers = new WorldViewerBucket(expectedViewers,
                        expectedQueries(worldId), adaptive);
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
            viewersByWorld.computeIfAbsent(worldId,
                            ignored -> new WorldViewerBucket(0, expectedQueries(worldId), adaptive))
                    .add(x, y, z);
        }

        boolean hasViewerWithin(UUID worldId, double x, double y, double z, double range) {
            if (range < 0.0D) {
                return false;
            }
            WorldViewerBucket viewers = viewersByWorld == null
                    ? java.util.Objects.equals(singleWorldId, worldId) ? singleWorldViewers : null
                    : viewersByWorld.get(worldId);
            return viewers != null && viewers.hasViewerWithin(x, y, z, range);
        }

        boolean hasAdaptiveGrid(UUID worldId) {
            WorldViewerBucket viewers = viewersByWorld == null
                    ? java.util.Objects.equals(singleWorldId, worldId) ? singleWorldViewers : null
                    : viewersByWorld.get(worldId);
            return viewers != null && viewers.viewerGrid != null;
        }

        boolean isUsingAdaptiveGrid(UUID worldId) {
            WorldViewerBucket viewers = viewersByWorld == null
                    ? java.util.Objects.equals(singleWorldId, worldId) ? singleWorldViewers : null
                    : viewersByWorld.get(worldId);
            return viewers != null && viewers.useGrid;
        }

        boolean hasActiveBounds(UUID worldId) {
            WorldViewerBucket viewers = viewersByWorld == null
                    ? java.util.Objects.equals(singleWorldId, worldId) ? singleWorldViewers : null
                    : viewersByWorld.get(worldId);
            return viewers != null && viewers.boundsActive;
        }

        private int expectedQueries(UUID worldId) {
            if (!usesPerWorldQueryBudgets) {
                return expectedQueries;
            }
            if (expectedQueriesByWorld == null) {
                return java.util.Objects.equals(singleExpectedQueryWorldId, worldId)
                        ? singleWorldExpectedQueries : 0;
            }
            return expectedQueriesByWorld.getOrDefault(worldId, 0);
        }

        private static int saturatedIncrement(int value) {
            return value == Integer.MAX_VALUE ? Integer.MAX_VALUE : value + 1;
        }

        private static int saturatedAdd(int first, int second) {
            return first > Integer.MAX_VALUE - second ? Integer.MAX_VALUE : first + second;
        }

        private static final class WorldViewerBucket {

            private static final int INITIAL_CAPACITY = 8;
            private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;
            private static final double[] EMPTY = new double[0];
            private static final double GRID_CELL_SIZE = 16.0D;
            private static final int MINIMUM_GRID_VIEWERS = 768;
            private static final int INTERNAL_MISSES_BEFORE_GRID = 8;
            private static final int MINIMUM_REMAINING_QUERIES = 256;
            private static final int VIEWERS_PER_QUERIED_CELL = 7;

            private double[] xCoordinates = EMPTY;
            private double[] yCoordinates = EMPTY;
            private double[] zCoordinates = EMPTY;
            private double minimumX = Double.POSITIVE_INFINITY;
            private double minimumY = Double.POSITIVE_INFINITY;
            private double minimumZ = Double.POSITIVE_INFINITY;
            private double maximumX = Double.NEGATIVE_INFINITY;
            private double maximumY = Double.NEGATIVE_INFINITY;
            private double maximumZ = Double.NEGATIVE_INFINITY;
            private final int expectedQueries;
            private final boolean adaptive;
            private Map<ViewerGridCell, List<Point>> viewerGrid;
            private boolean boundsInitialized;
            private boolean boundsActive;
            private boolean useGrid;
            private int completedQueries;
            private int internalMisses;
            private int size;

            private WorldViewerBucket(int initialCapacity, int expectedQueries, boolean adaptive) {
                this.expectedQueries = expectedQueries;
                this.adaptive = adaptive;
                if (initialCapacity > 0) {
                    xCoordinates = new double[initialCapacity];
                    yCoordinates = new double[initialCapacity];
                    zCoordinates = new double[initialCapacity];
                }
            }

            private void add(double x, double y, double z) {
                if (size == xCoordinates.length) {
                    int capacity = grownCapacity(size);
                    xCoordinates = grow(xCoordinates, capacity);
                    yCoordinates = grow(yCoordinates, capacity);
                    zCoordinates = grow(zCoordinates, capacity);
                }
                xCoordinates[size] = x;
                yCoordinates[size] = y;
                zCoordinates[size] = z;
                size++;
                if (adaptive && (boundsInitialized || viewerGrid != null || completedQueries != 0)) {
                    resetAdaptiveState();
                }
            }

            private boolean isOutsideBounds(double x, double y, double z, double range) {
                return x < minimumX - range || x > maximumX + range
                        || y < minimumY - range || y > maximumY + range
                        || z < minimumZ - range || z > maximumZ + range;
            }

            private boolean hasViewerWithin(double x, double y, double z, double range) {
                if (!adaptive) {
                    return hasViewerWithinLinear(x, y, z, range * range);
                }
                completedQueries++;
                if (boundsActive && isOutsideBounds(x, y, z, range)) {
                    return false;
                }
                double rangeSquared = range * range;
                if (useGrid) {
                    ViewerCellWindow window = gridWindowIfCostEffective(x, z, range);
                    if (window == null) {
                        useGrid = false;
                    } else {
                        if (hasViewerWithinGrid(x, y, z, rangeSquared, window)) {
                            useGrid = false;
                            boundsActive = false;
                            internalMisses = 0;
                            return true;
                        }
                        return false;
                    }
                }
                if (hasViewerWithinLinear(x, y, z, rangeSquared)) {
                    if (boundsActive || internalMisses != 0) {
                        boundsActive = false;
                        internalMisses = 0;
                    }
                    return true;
                }
                initializeBounds();
                boundsActive = true;
                if (isOutsideBounds(x, y, z, range)) {
                    return false;
                }
                internalMisses++;
                if (shouldUseGrid(x, z, range)) {
                    if (viewerGrid == null) {
                        buildGrid();
                    }
                    useGrid = true;
                }
                return false;
            }

            private boolean hasViewerWithinLinear(double x, double y, double z, double rangeSquared) {
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

            private boolean hasViewerWithinGrid(double x, double y, double z, double rangeSquared,
                                                ViewerCellWindow window) {
                int cellX = window.minimumX();
                while (true) {
                    int cellZ = window.minimumZ();
                    while (true) {
                        List<Point> points = viewerGrid.get(new ViewerGridCell(cellX, cellZ));
                        if (points != null) {
                            for (Point point : points) {
                                double deltaX = point.x() - x;
                                double deltaY = point.y() - y;
                                double deltaZ = point.z() - z;
                                if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= rangeSquared) {
                                    return true;
                                }
                            }
                        }
                        if (cellZ == window.maximumZ()) {
                            break;
                        }
                        cellZ++;
                    }
                    if (cellX == window.maximumX()) {
                        break;
                    }
                    cellX++;
                }
                return false;
            }

            private boolean shouldUseGrid(double x, double z, double range) {
                if (size < MINIMUM_GRID_VIEWERS
                        || internalMisses < INTERNAL_MISSES_BEFORE_GRID
                        || expectedQueries - completedQueries < MINIMUM_REMAINING_QUERIES) {
                    return false;
                }
                return gridWindowIfCostEffective(x, z, range) != null;
            }

            private ViewerCellWindow gridWindowIfCostEffective(double x, double z, double range) {
                if (!Double.isFinite(x) || !Double.isFinite(z)
                        || !Double.isFinite(range) || range < 0.0D) {
                    return null;
                }
                double minimumCellXValue = Math.floor((x - range) / GRID_CELL_SIZE);
                double maximumCellXValue = Math.floor((x + range) / GRID_CELL_SIZE);
                double minimumCellZValue = Math.floor((z - range) / GRID_CELL_SIZE);
                double maximumCellZValue = Math.floor((z + range) / GRID_CELL_SIZE);
                if (!isIntCell(minimumCellXValue) || !isIntCell(maximumCellXValue)
                        || !isIntCell(minimumCellZValue) || !isIntCell(maximumCellZValue)) {
                    return null;
                }
                int minimumCellX = (int) minimumCellXValue;
                int maximumCellX = (int) maximumCellXValue;
                int minimumCellZ = (int) minimumCellZValue;
                int maximumCellZ = (int) maximumCellZValue;
                long queriedCellsX = (long) maximumCellX - minimumCellX + 1L;
                long queriedCellsZ = (long) maximumCellZ - minimumCellZ + 1L;
                long maximumQueriedCells = size / VIEWERS_PER_QUERIED_CELL;
                if (queriedCellsX <= 0L || queriedCellsZ <= 0L || maximumQueriedCells <= 0L
                        || queriedCellsX > maximumQueriedCells
                        || queriedCellsZ > maximumQueriedCells
                        || queriedCellsX > maximumQueriedCells / queriedCellsZ) {
                    return null;
                }
                return new ViewerCellWindow(minimumCellX, maximumCellX, minimumCellZ, maximumCellZ);
            }

            private void initializeBounds() {
                if (boundsInitialized) {
                    return;
                }
                double nextMinimumX = Double.POSITIVE_INFINITY;
                double nextMinimumY = Double.POSITIVE_INFINITY;
                double nextMinimumZ = Double.POSITIVE_INFINITY;
                double nextMaximumX = Double.NEGATIVE_INFINITY;
                double nextMaximumY = Double.NEGATIVE_INFINITY;
                double nextMaximumZ = Double.NEGATIVE_INFINITY;
                for (int index = 0; index < size; index++) {
                    nextMinimumX = Math.min(nextMinimumX, xCoordinates[index]);
                    nextMinimumY = Math.min(nextMinimumY, yCoordinates[index]);
                    nextMinimumZ = Math.min(nextMinimumZ, zCoordinates[index]);
                    nextMaximumX = Math.max(nextMaximumX, xCoordinates[index]);
                    nextMaximumY = Math.max(nextMaximumY, yCoordinates[index]);
                    nextMaximumZ = Math.max(nextMaximumZ, zCoordinates[index]);
                }
                minimumX = nextMinimumX;
                minimumY = nextMinimumY;
                minimumZ = nextMinimumZ;
                maximumX = nextMaximumX;
                maximumY = nextMaximumY;
                maximumZ = nextMaximumZ;
                boundsInitialized = true;
            }

            private void buildGrid() {
                Map<ViewerGridCell, List<Point>> grid = new HashMap<>(viewerGridCapacity(size));
                for (int index = 0; index < size; index++) {
                    ViewerGridCell cell = new ViewerGridCell(viewerCoordinate(xCoordinates[index]),
                            viewerCoordinate(zCoordinates[index]));
                    grid.computeIfAbsent(cell, ignored -> new ArrayList<>())
                            .add(new Point(xCoordinates[index], yCoordinates[index], zCoordinates[index]));
                }
                viewerGrid = grid;
            }

            private void resetAdaptiveState() {
                viewerGrid = null;
                boundsInitialized = false;
                boundsActive = false;
                useGrid = false;
                completedQueries = 0;
                internalMisses = 0;
                minimumX = Double.POSITIVE_INFINITY;
                minimumY = Double.POSITIVE_INFINITY;
                minimumZ = Double.POSITIVE_INFINITY;
                maximumX = Double.NEGATIVE_INFINITY;
                maximumY = Double.NEGATIVE_INFINITY;
                maximumZ = Double.NEGATIVE_INFINITY;
            }

            private static int viewerCoordinate(double coordinate) {
                return (int) Math.floor(coordinate / GRID_CELL_SIZE);
            }

            private static boolean isIntCell(double value) {
                return Double.isFinite(value) && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
            }

            private static int viewerGridCapacity(int viewers) {
                return viewers < (1 << 29) ? Math.max(16, (int) (viewers / 0.75F) + 1) : 1 << 30;
            }

            private static int grownCapacity(int size) {
                if (size >= MAX_ARRAY_SIZE) {
                    throw new OutOfMemoryError("Too many viewers");
                }
                return size > MAX_ARRAY_SIZE / 2
                        ? MAX_ARRAY_SIZE
                        : Math.max(INITIAL_CAPACITY, size << 1);
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

    private record ViewerGridCell(int x, int z) {
    }

    private record ViewerCellWindow(int minimumX, int maximumX, int minimumZ, int maximumZ) {
    }

    private record Point(double x, double y, double z) {
    }
}
