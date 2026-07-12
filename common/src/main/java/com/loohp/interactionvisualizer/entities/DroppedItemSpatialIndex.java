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
    private static final double VIEWER_CELL_SIZE = 16.0D;

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

        private final Map<ViewerCell, List<Point>> viewerCells = new HashMap<>();

        void addViewer(UUID worldId, double x, double y, double z) {
            viewerCells.computeIfAbsent(viewerCell(worldId, x, z), ignored -> new ArrayList<>())
                    .add(new Point(x, y, z));
        }

        boolean isEmpty() {
            return viewerCells.isEmpty();
        }

        boolean hasViewerWithin(UUID worldId, double x, double y, double z, double range) {
            if (range < 0.0D) {
                return false;
            }

            int minimumX = viewerCoordinate(x - range);
            int maximumX = viewerCoordinate(x + range);
            int minimumZ = viewerCoordinate(z - range);
            int maximumZ = viewerCoordinate(z + range);
            double rangeSquared = range * range;

            for (int cellX = minimumX; cellX <= maximumX; cellX++) {
                for (int cellZ = minimumZ; cellZ <= maximumZ; cellZ++) {
                    List<Point> points = viewerCells.get(new ViewerCell(worldId, cellX, cellZ));
                    if (points == null) {
                        continue;
                    }
                    for (Point point : points) {
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

        private static ViewerCell viewerCell(UUID worldId, double x, double z) {
            return new ViewerCell(worldId, viewerCoordinate(x), viewerCoordinate(z));
        }

        private static int viewerCoordinate(double coordinate) {
            return (int) Math.floor(coordinate / VIEWER_CELL_SIZE);
        }
    }

    private record Cell(UUID worldId, int x, int y, int z) {
    }

    private record ViewerCell(UUID worldId, int x, int z) {
    }

    private record Point(double x, double y, double z) {
    }
}
