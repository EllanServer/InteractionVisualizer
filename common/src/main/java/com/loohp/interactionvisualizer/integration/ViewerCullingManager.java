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

import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.Set;

/** Optional second-stage occlusion culling for sent-chunk display candidates. */
public interface ViewerCullingManager {

    ViewerCullingManager DISABLED = new ViewerCullingManager() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public boolean track(Player viewer, UUID logicalId, CullingBounds bounds) {
            return false;
        }

        @Override
        public void update(UUID logicalId, CullingBounds bounds) {
        }

        @Override
        public void untrack(UUID viewerId, UUID logicalId) {
        }

        @Override
        public void clearViewer(UUID viewerId) {
        }

        @Override
        public void clearLogical(UUID logicalId) {
        }

        @Override
        public void retainLogical(UUID logicalId, Set<UUID> viewerIds) {
        }

        @Override
        public void shutdown() {
        }

        @Override
        public int retainedRegistrations() {
            return 0;
        }
    };

    boolean enabled();

    /** Returns true when this backend owns visibility for the candidate. */
    boolean track(Player viewer, UUID logicalId, CullingBounds bounds);

    void update(UUID logicalId, CullingBounds bounds);

    void untrack(UUID viewerId, UUID logicalId);

    void clearViewer(UUID viewerId);

    void clearLogical(UUID logicalId);

    /** Removes registrations for this logical that are no longer local candidates. */
    void retainLogical(UUID logicalId, Set<UUID> viewerIds);

    void shutdown();

    int retainedRegistrations();

    @FunctionalInterface
    interface VisibilityListener {

        void visibilityChanged(UUID viewerId, UUID logicalId, boolean visible);
    }
}
