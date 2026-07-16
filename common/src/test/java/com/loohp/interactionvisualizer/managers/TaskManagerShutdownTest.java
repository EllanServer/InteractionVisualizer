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

package com.loohp.interactionvisualizer.managers;

import com.loohp.interactionvisualizer.api.VisualizerRunnableDisplay;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import org.bukkit.event.inventory.InventoryType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskManagerShutdownTest {

    @Test
    void clearsRegistriesAndPendingPlayerRequests() {
        UUID playerId = UUID.randomUUID();
        Map<InventoryType, List<com.loohp.interactionvisualizer.api.VisualizerInteractDisplay>> originalProcesses =
                TaskManager.processes;
        Map<InventoryType, List<com.loohp.interactionvisualizer.api.VisualizerInteractDisplay>> originalProcessEntries =
                new HashMap<>();
        originalProcesses.forEach((type, displays) ->
                originalProcessEntries.put(type, new ArrayList<>(displays)));
        List<VisualizerRunnableDisplay> originalRunnables = new ArrayList<>(TaskManager.runnables);
        TrackingMap<InventoryType, List<com.loohp.interactionvisualizer.api.VisualizerInteractDisplay>> processes =
                new TrackingMap<>();
        try {
            TaskManager.processes = processes;
            TaskManager.runnables.add(runnableDisplay());
            assertTrue(TaskManager.markInventoryOpenProcessQueued(playerId));

            TaskManager.clearRuntimeState();

            assertTrue(processes.cleared);
            assertTrue(TaskManager.processes.isEmpty());
            assertTrue(TaskManager.runnables.isEmpty());
            assertTrue(TaskManager.markInventoryOpenProcessQueued(playerId),
                    "shutdown must release the pending request token");
        } finally {
            TaskManager.clearRuntimeState();
            TaskManager.processes = originalProcesses;
            originalProcesses.clear();
            originalProcesses.putAll(originalProcessEntries);
            TaskManager.runnables.addAll(originalRunnables);
        }
    }

    private static VisualizerRunnableDisplay runnableDisplay() {
        return new VisualizerRunnableDisplay() {
            @Override
            public EntryKey key() {
                return new EntryKey("shutdown_runnable_test");
            }

            @Override
            public ScheduledTask gc() {
                return null;
            }

            @Override
            public ScheduledTask run() {
                return null;
            }
        };
    }

    private static final class TrackingMap<K, V> extends ConcurrentHashMap<K, V> {

        private boolean cleared;

        @Override
        public void clear() {
            cleared = true;
            super.clear();
        }
    }
}
