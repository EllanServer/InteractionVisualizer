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

package com.loohp.interactionvisualizer.integration;

/** Immutable provider-neutral bounds for one logical display. */
public record CullingBounds(
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        int maxDistance, double expansion, boolean rayTracing) {

    public CullingBounds {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || !Double.isFinite(expansion)) {
            throw new IllegalArgumentException("Culling bounds must be finite");
        }
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("Culling bounds must be ordered");
        }
        if (maxDistance < 0 || expansion < 0.0D) {
            throw new IllegalArgumentException("Culling distance and expansion cannot be negative");
        }
    }
}
