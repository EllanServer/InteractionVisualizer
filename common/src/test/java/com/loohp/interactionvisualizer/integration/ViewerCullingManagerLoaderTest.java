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
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViewerCullingManagerLoaderTest {

    @Test
    void loadsTheImplementationOnlyAfterItsSentinelIsVisible() throws Exception {
        ViewerCullingManager manager = ViewerCullingManagerLoader.load(
                getClass().getClassLoader(), "java.lang.String",
                FakeViewerCullingManager.class.getName(), null,
                (viewer, logical, visible) -> {
                });

        assertInstanceOf(FakeViewerCullingManager.class, manager);
    }

    @Test
    void leavesCraftEngineClassesUnresolvedWhenTheProviderIsAbsent() {
        assertThrows(ClassNotFoundException.class, () -> ViewerCullingManagerLoader.load(
                getClass().getClassLoader(), ViewerCullingManagerLoader.CRAFT_ENGINE_SENTINEL,
                ViewerCullingManagerLoader.CRAFT_ENGINE_IMPLEMENTATION, null,
                (viewer, logical, visible) -> {
                }));
    }

    @Test
    void rejectsImplementationsOutsideTheNeutralContract() {
        assertThrows(ClassCastException.class, () -> ViewerCullingManagerLoader.load(
                getClass().getClassLoader(), "java.lang.String", "java.lang.String",
                null, (viewer, logical, visible) -> {
                }));
    }

    public static final class FakeViewerCullingManager implements ViewerCullingManager {

        public FakeViewerCullingManager(Plugin plugin, VisibilityListener listener) {
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public boolean track(Player viewer, UUID logicalId, CullingBounds bounds) {
            return true;
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
    }
}
