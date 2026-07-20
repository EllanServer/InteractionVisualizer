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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskManagerShutdownTest {

    @Test
    @SuppressWarnings("deprecation")
    void clearsRegistriesAndInvokesRunnableShutdownHooks() {
        UUID playerId = UUID.randomUUID();
        Map<InventoryType, List<com.loohp.interactionvisualizer.api.VisualizerInteractDisplay>> originalProcesses =
                TaskManager.processes;
        List<VisualizerRunnableDisplay> originalRunnables = TaskManager.runnables;
        TrackingMap<InventoryType, List<com.loohp.interactionvisualizer.api.VisualizerInteractDisplay>> processes =
                new TrackingMap<>();
        AtomicInteger failingUnregisterCalls = new AtomicInteger();
        AtomicInteger successfulUnregisterCalls = new AtomicInteger();
        try {
            TaskManager.processes = processes;
            TaskManager.runnables = new ArrayList<>();
            runnableDisplay(failingUnregisterCalls, true).registerNative();
            runnableDisplay(successfulUnregisterCalls, false).registerNative();
            assertTrue(TaskManager.markInventoryOpenProcessQueued(playerId));

            assertDoesNotThrow(TaskManager::clearRuntimeState);

            assertTrue(processes.cleared);
            assertTrue(TaskManager.processes.isEmpty());
            assertTrue(TaskManager.runnables.isEmpty());
            assertEquals(1, failingUnregisterCalls.get());
            assertEquals(1, successfulUnregisterCalls.get());
            assertTrue(TaskManager.markInventoryOpenProcessQueued(playerId),
                    "shutdown must release the pending request token");
        } finally {
            TaskManager.clearRuntimeState();
            TaskManager.processes = originalProcesses;
            TaskManager.runnables = originalRunnables;
        }
    }

    private static VisualizerRunnableDisplay runnableDisplay(AtomicInteger unregisterCalls,
                                                               boolean failOnUnregister) {
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

            @Override
            protected void onUnregister() {
                unregisterCalls.incrementAndGet();
                if (failOnUnregister) {
                    throw new AssertionError("expected shutdown test failure");
                }
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
