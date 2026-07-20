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

package com.loohp.interactionvisualizer.api;

import com.loohp.interactionvisualizer.managers.TaskManager;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualizerRunnableDisplayLifecycleTest {

    @Test
    @SuppressWarnings("deprecation")
    void unregisterCancelsBaseTasksAndRunsHookOnlyOnce() throws Exception {
        List<VisualizerRunnableDisplay> originalRunnables = TaskManager.runnables;
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger unregisterCalls = new AtomicInteger();
        TaskManager.runnables = new ArrayList<>();
        try {
            ScheduledTask gcTask = scheduledTask(cancellations);
            ScheduledTask runTask = scheduledTask(cancellations);
            VisualizerRunnableDisplay display = new VisualizerRunnableDisplay() {
                @Override
                public EntryKey key() {
                    return new EntryKey("runnable_lifecycle_test");
                }

                @Override
                public ScheduledTask gc() {
                    return gcTask;
                }

                @Override
                public ScheduledTask run() {
                    return runTask;
                }

                @Override
                protected void onUnregister() {
                    unregisterCalls.incrementAndGet();
                }
            };

            display.registerNative();
            assertTrue(TaskManager.runnables.contains(display));

            display.unregister();
            display.unregister();

            assertEquals(2, cancellations.get());
            assertEquals(1, unregisterCalls.get());
            assertFalse(TaskManager.runnables.contains(display));
        } finally {
            TaskManager.runnables = originalRunnables;
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void unregisterContinuesAfterCancelErrorAndRethrowsOriginalError() throws Exception {
        List<VisualizerRunnableDisplay> originalRunnables = TaskManager.runnables;
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger unregisterCalls = new AtomicInteger();
        AssertionError cancellationFailure = new AssertionError("expected cancel failure");
        TaskManager.runnables = new ArrayList<>();
        try {
            ScheduledTask failingTask = scheduledTask(cancellations, cancellationFailure);
            ScheduledTask successfulTask = scheduledTask(cancellations);
            VisualizerRunnableDisplay display = new VisualizerRunnableDisplay() {
                @Override
                public EntryKey key() {
                    return new EntryKey("runnable_error_lifecycle_test");
                }

                @Override
                public ScheduledTask gc() {
                    return failingTask;
                }

                @Override
                public ScheduledTask run() {
                    return successfulTask;
                }

                @Override
                protected void onUnregister() {
                    unregisterCalls.incrementAndGet();
                }
            };

            display.registerNative();

            AssertionError thrown = assertThrows(AssertionError.class, display::unregister);

            assertSame(cancellationFailure, thrown);
            assertEquals(2, cancellations.get());
            assertEquals(1, unregisterCalls.get());
            assertFalse(TaskManager.runnables.contains(display));
        } finally {
            TaskManager.runnables = originalRunnables;
        }
    }

    private static ScheduledTask scheduledTask(AtomicInteger cancellations) throws Exception {
        return scheduledTask(cancellations, null);
    }

    private static ScheduledTask scheduledTask(AtomicInteger cancellations, Throwable failure) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        BukkitTask bukkitTask = (BukkitTask) Proxy.newProxyInstance(
                BukkitTask.class.getClassLoader(), new Class<?>[]{BukkitTask.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "cancel" -> {
                        cancellations.incrementAndGet();
                        cancelled.set(true);
                        if (failure != null) {
                            throw failure;
                        }
                        yield null;
                    }
                    case "isCancelled" -> cancelled.get();
                    case "getTaskId" -> 1;
                    case "getOwner" -> null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "LifecycleTestBukkitTask";
                    default -> defaultValue(method.getReturnType());
                });
        Constructor<ScheduledTask> constructor = ScheduledTask.class.getDeclaredConstructor(BukkitTask.class);
        constructor.setAccessible(true);
        return constructor.newInstance(bukkitTask);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class || type == short.class || type == int.class || type == long.class) {
            return 0;
        }
        if (type == float.class || type == double.class) {
            return 0.0D;
        }
        return null;
    }
}
