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
            viewersByWorld.computeIfAbsent(worldId,
                            ignored -> new WorldViewerBucket(0))
                    .add(x, y, z);
        }

        boolean hasViewerWithin(UUID worldId, double x, double y, double z, double range) {
            return hasViewerWithin(worldId, x, y, z, range, Integer.MAX_VALUE);
        }

        boolean hasViewerWithin(UUID worldId, double x, double y, double z,
                                double range, int remainingQueries) {
            if (range < 0.0D) {
                return false;
            }
            WorldViewerBucket viewers = viewersByWorld == null
                    ? java.util.Objects.equals(singleWorldId, worldId) ? singleWorldViewers : null
                    : viewersByWorld.get(worldId);
            if (viewers == null) {
                return false;
            }
            double rangeSquared = range * range;
            if (!viewers.boundsActive && !viewers.useGrid) {
                double[] xCoordinates = viewers.xCoordinates;
                double[] yCoordinates = viewers.yCoordinates;
                double[] zCoordinates = viewers.zCoordinates;
                int viewerCount = viewers.size;
                for (int index = 0; index < viewerCount; index++) {
                    double deltaX = xCoordinates[index] - x;
                    double deltaY = yCoordinates[index] - y;
                    double deltaZ = zCoordinates[index] - z;
                    if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= rangeSquared) {
                        int matchingViewer = index + 1;
                        if (matchingViewer < WorldViewerBucket.MINIMUM_DEEP_SCAN_VIEWERS) {
                            if (viewers.expensiveQueries != 0 || viewers.persistentGridHits) {
                                viewers.expensiveQueries = 0;
                                viewers.persistentGridHits = false;
                            }
                            return true;
                        }
                        int expensiveQueries = ++viewers.expensiveQueries;
                        if (expensiveQueries >= WorldViewerBucket.EXPENSIVE_QUERIES_BEFORE_GRID
                                && (viewers.viewerGrid != null
                                    || remainingQueries >= WorldViewerBucket.MINIMUM_REMAINING_QUERIES)) {
                            viewers.activateGridIfBeneficial(x, z, range, remainingQueries,
                                    matchingViewer, matchingViewer - 1, true);
                        }
                        return true;
                    }
                }
                if (!viewers.boundsInitialized) {
                    viewers.initializeBounds();
                }
                if (viewers.isOutsideBounds(x, y, z, range)) {
                    viewers.boundsActive = true;
                    return false;
                }
                int expensiveQueries = ++viewers.expensiveQueries;
                if (expensiveQueries >= WorldViewerBucket.EXPENSIVE_QUERIES_BEFORE_GRID
                        && (viewers.viewerGrid != null
                            || remainingQueries >= WorldViewerBucket.MINIMUM_REMAINING_QUERIES)) {
                    viewers.activateGridIfBeneficial(x, z, range, remainingQueries,
                            viewerCount, -1, false);
                }
                return false;
            }
            return viewers.hasViewerWithinAdaptiveState(
                    x, y, z, range, rangeSquared, remainingQueries);
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

        private static final class WorldViewerBucket {

            private static final int INITIAL_CAPACITY = 8;
            private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;
            private static final double[] EMPTY = new double[0];
            private static final double GRID_CELL_SIZE = 16.0D;
            private static final int MINIMUM_DEEP_SCAN_VIEWERS = 128;
            private static final int EXPENSIVE_QUERIES_BEFORE_GRID = 8;
            private static final int MINIMUM_REMAINING_QUERIES = 256;
            private static final int CELL_LOOKUP_EQUIVALENT_VIEWERS = 3;
            private static final int GRID_BUILD_EQUIVALENT_VIEWERS_PER_VIEWER = 4;
            private static final int GRID_LINEAR_PROBE_INTERVAL = 64;

            private double[] xCoordinates = EMPTY;
            private double[] yCoordinates = EMPTY;
            private double[] zCoordinates = EMPTY;
            private double minimumX = Double.POSITIVE_INFINITY;
            private double minimumY = Double.POSITIVE_INFINITY;
            private double minimumZ = Double.POSITIVE_INFINITY;
            private double maximumX = Double.NEGATIVE_INFINITY;
            private double maximumY = Double.NEGATIVE_INFINITY;
            private double maximumZ = Double.NEGATIVE_INFINITY;
            private Map<ViewerGridCell, List<Point>> viewerGrid;
            private boolean boundsInitialized;
            private boolean boundsActive;
            private boolean useGrid;
            private boolean persistentGridHits;
            private int expensiveQueries;
            private int gridQueriesUntilProbe;
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
                    int capacity = grownCapacity(size);
                    xCoordinates = grow(xCoordinates, capacity);
                    yCoordinates = grow(yCoordinates, capacity);
                    zCoordinates = grow(zCoordinates, capacity);
                }
                xCoordinates[size] = x;
                yCoordinates[size] = y;
                zCoordinates[size] = z;
                size++;
                if (boundsInitialized || viewerGrid != null || expensiveQueries != 0) {
                    resetAdaptiveState();
                }
            }

            private boolean isOutsideBounds(double x, double y, double z, double range) {
                return x < minimumX - range || x > maximumX + range
                        || y < minimumY - range || y > maximumY + range
                        || z < minimumZ - range || z > maximumZ + range;
            }

            private boolean hasViewerWithinAdaptiveState(double x, double y, double z, double range,
                                                         double rangeSquared, int remainingQueries) {
                if (boundsActive && isOutsideBounds(x, y, z, range)) {
                    return false;
                }
                if (useGrid) {
                    ViewerCellWindow window = gridWindow(x, z, range);
                    if (window == null) {
                        useGrid = false;
                        persistentGridHits = false;
                        expensiveQueries = 0;
                        gridQueriesUntilProbe = 0;
                    } else if (--gridQueriesUntilProbe <= 0) {
                        useGrid = false;
                        persistentGridHits = false;
                        expensiveQueries = EXPENSIVE_QUERIES_BEFORE_GRID - 1;
                    } else {
                        if (hasViewerWithinGrid(x, y, z, rangeSquared, window)) {
                            if (boundsActive) {
                                boundsActive = false;
                            }
                            if (!persistentGridHits) {
                                useGrid = false;
                                expensiveQueries = 0;
                                gridQueriesUntilProbe = 0;
                            }
                            return true;
                        }
                        if (isOutsideBounds(x, y, z, range)) {
                            boundsActive = true;
                        }
                        return false;
                    }
                }
                int matchingViewer = firstViewerWithin(x, y, z, rangeSquared);
                return resolveLinearResult(x, y, z, range, remainingQueries, matchingViewer);
            }

            private boolean resolveLinearResult(double x, double y, double z, double range,
                                                int remainingQueries, int matchingViewer) {
                if (matchingViewer != 0) {
                    if (boundsActive) {
                        boundsActive = false;
                    }
                    if (matchingViewer >= MINIMUM_DEEP_SCAN_VIEWERS) {
                        expensiveQueries++;
                        if (expensiveQueries >= EXPENSIVE_QUERIES_BEFORE_GRID
                                && (viewerGrid != null || remainingQueries >= MINIMUM_REMAINING_QUERIES)) {
                            activateGridIfBeneficial(x, z, range, remainingQueries,
                                    matchingViewer, matchingViewer - 1, true);
                        }
                    } else if (expensiveQueries != 0 || persistentGridHits) {
                        expensiveQueries = 0;
                        persistentGridHits = false;
                    }
                    return true;
                }
                initializeBounds();
                if (isOutsideBounds(x, y, z, range)) {
                    boundsActive = true;
                    return false;
                }
                boundsActive = false;
                expensiveQueries++;
                if (expensiveQueries >= EXPENSIVE_QUERIES_BEFORE_GRID
                        && (viewerGrid != null || remainingQueries >= MINIMUM_REMAINING_QUERIES)) {
                    activateGridIfBeneficial(x, z, range, remainingQueries, size, -1, false);
                }
                return false;
            }

            private int firstViewerWithin(double x, double y, double z, double rangeSquared) {
                double[] xCoordinates = this.xCoordinates;
                double[] yCoordinates = this.yCoordinates;
                double[] zCoordinates = this.zCoordinates;
                int viewerCount = size;
                for (int index = 0; index < viewerCount; index++) {
                    double deltaX = xCoordinates[index] - x;
                    double deltaY = yCoordinates[index] - y;
                    double deltaZ = zCoordinates[index] - z;
                    if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= rangeSquared) {
                        return index + 1;
                    }
                }
                return 0;
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

            private void activateGridIfBeneficial(double x, double z, double range,
                                                  int remainingQueries, int scannedViewers,
                                                  int matchingViewerIndex, boolean persistOnHits) {
                if (expensiveQueries < EXPENSIVE_QUERIES_BEFORE_GRID
                        || (viewerGrid == null && remainingQueries < MINIMUM_REMAINING_QUERIES)) {
                    return;
                }
                ViewerCellWindow window = gridWindow(x, z, range);
                if (window == null || !gridLikelyCheaper(
                        window, scannedViewers, matchingViewerIndex, remainingQueries)) {
                    expensiveQueries = EXPENSIVE_QUERIES_BEFORE_GRID - GRID_LINEAR_PROBE_INTERVAL;
                    return;
                }
                if (viewerGrid == null) {
                    buildGrid();
                }
                useGrid = true;
                persistentGridHits = persistOnHits;
                expensiveQueries = 0;
                gridQueriesUntilProbe = GRID_LINEAR_PROBE_INTERVAL;
            }

            private boolean gridLikelyCheaper(ViewerCellWindow window, int scannedViewers,
                                              int matchingViewerIndex, int remainingQueries) {
                long finalCell = window.cellCount();
                if (matchingViewerIndex >= 0) {
                    long matchingCell = window.ordinal(viewerCoordinate(xCoordinates[matchingViewerIndex]),
                            viewerCoordinate(zCoordinates[matchingViewerIndex]));
                    if (matchingCell > 0L) {
                        finalCell = matchingCell;
                    }
                }
                long cellCost = finalCell * CELL_LOOKUP_EQUIVALENT_VIEWERS;
                if (cellCost >= scannedViewers) {
                    return false;
                }
                long maximumVisitedViewers = scannedViewers - cellCost - 1L;
                int visitedViewers = 0;
                for (int index = 0; index < size; index++) {
                    long ordinal = window.ordinal(viewerCoordinate(xCoordinates[index]),
                            viewerCoordinate(zCoordinates[index]));
                    if (ordinal > 0L && (ordinal < finalCell
                            || ordinal == finalCell && (matchingViewerIndex < 0 || index <= matchingViewerIndex))) {
                        visitedViewers++;
                        if (visitedViewers > maximumVisitedViewers) {
                            return false;
                        }
                    }
                }
                long perQuerySaving = scannedViewers - cellCost - visitedViewers;
                long futureQueries = Math.max(0L, remainingQueries);
                long linearProbeCost = ((futureQueries + GRID_LINEAR_PROBE_INTERVAL - 1L)
                        / GRID_LINEAR_PROBE_INTERVAL) * scannedViewers;
                long totalSaving = perQuerySaving * futureQueries - linearProbeCost;
                long buildCost = viewerGrid == null
                        ? (long) size * GRID_BUILD_EQUIVALENT_VIEWERS_PER_VIEWER : 0L;
                return totalSaving > buildCost;
            }

            private ViewerCellWindow gridWindow(double x, double z, double range) {
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
                long maximumQueriedCells = size;
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
                boolean calculateBounds = !boundsInitialized;
                double nextMinimumX = Double.POSITIVE_INFINITY;
                double nextMinimumY = Double.POSITIVE_INFINITY;
                double nextMinimumZ = Double.POSITIVE_INFINITY;
                double nextMaximumX = Double.NEGATIVE_INFINITY;
                double nextMaximumY = Double.NEGATIVE_INFINITY;
                double nextMaximumZ = Double.NEGATIVE_INFINITY;
                for (int index = 0; index < size; index++) {
                    ViewerGridCell cell = new ViewerGridCell(viewerCoordinate(xCoordinates[index]),
                            viewerCoordinate(zCoordinates[index]));
                    grid.computeIfAbsent(cell, ignored -> new ArrayList<>())
                            .add(new Point(xCoordinates[index], yCoordinates[index], zCoordinates[index]));
                    if (calculateBounds) {
                        nextMinimumX = Math.min(nextMinimumX, xCoordinates[index]);
                        nextMinimumY = Math.min(nextMinimumY, yCoordinates[index]);
                        nextMinimumZ = Math.min(nextMinimumZ, zCoordinates[index]);
                        nextMaximumX = Math.max(nextMaximumX, xCoordinates[index]);
                        nextMaximumY = Math.max(nextMaximumY, yCoordinates[index]);
                        nextMaximumZ = Math.max(nextMaximumZ, zCoordinates[index]);
                    }
                }
                if (calculateBounds) {
                    minimumX = nextMinimumX;
                    minimumY = nextMinimumY;
                    minimumZ = nextMinimumZ;
                    maximumX = nextMaximumX;
                    maximumY = nextMaximumY;
                    maximumZ = nextMaximumZ;
                    boundsInitialized = true;
                }
                viewerGrid = grid;
            }

            private void resetAdaptiveState() {
                viewerGrid = null;
                boundsInitialized = false;
                boundsActive = false;
                useGrid = false;
                persistentGridHits = false;
                expensiveQueries = 0;
                gridQueriesUntilProbe = 0;
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

        private long widthZ() {
            return (long) maximumZ - minimumZ + 1L;
        }

        private long cellCount() {
            return ((long) maximumX - minimumX + 1L) * widthZ();
        }

        private long ordinal(int x, int z) {
            if (x < minimumX || x > maximumX || z < minimumZ || z > maximumZ) {
                return -1L;
            }
            return ((long) x - minimumX) * widthZ() + (long) z - minimumZ + 1L;
        }
    }

    private record Point(double x, double y, double z) {
    }
}
