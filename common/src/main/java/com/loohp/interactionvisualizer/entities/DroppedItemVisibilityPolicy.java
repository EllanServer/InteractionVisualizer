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

/**
 * Validated configuration for the opt-in dropped-item visibility controls.
 * Disabled culling preserves the legacy server-side label lifecycle;
 * rate limiting is independently opt-in.
 */
record DroppedItemVisibilityPolicy(
        int viewDistance,
        boolean rateLimitEnabled,
        int bucketSize,
        int restorePerTick
) {

    static final int DEFAULT_BUCKET_SIZE = 128;
    static final int DEFAULT_RESTORE_PER_TICK = 32;
    static final int DEFAULT_VIEW_DISTANCE = 64;
    private static final int MIN_VIEW_DISTANCE = 8;
    private static final int MAX_VIEW_DISTANCE = 512;

    static DroppedItemVisibilityPolicy create(boolean cullingEnabled,
                                              int configuredViewDistance,
                                              boolean rateLimitEnabled,
                                              int configuredBucketSize,
                                              int configuredRestorePerTick) {
        int requestedViewDistance = configuredViewDistance > 0
                ? configuredViewDistance
                : DEFAULT_VIEW_DISTANCE;
        int viewDistance = cullingEnabled
                ? Math.max(MIN_VIEW_DISTANCE, Math.min(MAX_VIEW_DISTANCE, requestedViewDistance))
                : 0;
        int bucketSize = configuredBucketSize > 0
                ? configuredBucketSize
                : DEFAULT_BUCKET_SIZE;
        int restorePerTick = configuredRestorePerTick > 0
                ? configuredRestorePerTick
                : DEFAULT_RESTORE_PER_TICK;
        return new DroppedItemVisibilityPolicy(
                viewDistance, rateLimitEnabled, bucketSize, restorePerTick);
    }

    static DroppedItemVisibilityPolicy legacyDefaults() {
        return create(false, DEFAULT_VIEW_DISTANCE, false,
                DEFAULT_BUCKET_SIZE, DEFAULT_RESTORE_PER_TICK);
    }

    boolean cullingEnabled() {
        return viewDistance > 0;
    }

    boolean controlsPerViewerVisibility() {
        return cullingEnabled() || rateLimitEnabled;
    }

    int effectiveViewDistance(int trackingDistance) {
        if (!cullingEnabled()) {
            throw new IllegalStateException("View-distance culling is disabled");
        }
        return Math.min(viewDistance, Math.max(1, trackingDistance));
    }

    float labelViewRange() {
        if (!cullingEnabled()) {
            return 1.0F;
        }
        return (float) Math.max(0.125D, Math.min(8.0D, viewDistance / 64.0D));
    }
}
