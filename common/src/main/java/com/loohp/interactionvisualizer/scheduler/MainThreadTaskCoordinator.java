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

package com.loohp.interactionvisualizer.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/** One Bukkit driver task multiplexes every InteractionVisualizer task. */
final class MainThreadTaskCoordinator {

    private static final AtomicInteger NEXT_TASK_ID = new AtomicInteger(-1);
    private static final Map<Plugin, Coordinator> COORDINATORS = new ConcurrentHashMap<>();
    private static final Map<Integer, CoordinatedTask> TASKS_BY_ID = new ConcurrentHashMap<>();

    private MainThreadTaskCoordinator() {
    }

    static ScheduledTask schedule(Plugin plugin, Runnable runnable, long delay, long period) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(runnable, "runnable");
        Coordinator coordinator = COORDINATORS.computeIfAbsent(plugin, Coordinator::new);
        CoordinatedTask task = coordinator.schedule(runnable, delay, period);
        TASKS_BY_ID.put(task.getTaskId(), task);
        return new ScheduledTask(task);
    }

    static boolean cancel(int taskId) {
        CoordinatedTask task = TASKS_BY_ID.remove(taskId);
        if (task == null) {
            return false;
        }
        task.cancel();
        return true;
    }

    static void shutdown(Plugin plugin) {
        Coordinator coordinator = COORDINATORS.remove(plugin);
        if (coordinator != null) {
            coordinator.shutdown();
        }
    }

    static int retainedTaskCount(Plugin plugin) {
        Coordinator coordinator = COORDINATORS.get(plugin);
        return coordinator == null ? 0 : coordinator.retainedTaskCount();
    }

    private static final class Coordinator {

        private final Plugin plugin;
        private final ConcurrentLinkedQueue<CoordinatedTask> incoming = new ConcurrentLinkedQueue<>();
        private final PriorityQueue<CoordinatedTask> scheduled = new PriorityQueue<>(
                Comparator.comparingLong(CoordinatedTask::dueTick)
                        .thenComparingInt(CoordinatedTask::getTaskId));
        private final BukkitTask driver;
        private volatile long currentTick;
        private volatile boolean closed;

        private Coordinator(Plugin plugin) {
            this.plugin = plugin;
            this.currentTick = Integer.toUnsignedLong(Bukkit.getCurrentTick());
            this.driver = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }

        private CoordinatedTask schedule(Runnable runnable, long delay, long period) {
            if (closed) {
                throw new IllegalStateException("The task coordinator is shut down");
            }
            long firstTick = currentTick + Math.max(1L, delay);
            CoordinatedTask task = new CoordinatedTask(this, NEXT_TASK_ID.getAndDecrement(),
                    runnable, firstTick, period <= 0L ? 0L : Math.max(1L, period));
            incoming.add(task);
            return task;
        }

        private void tick() {
            if (closed) {
                return;
            }
            currentTick = Integer.toUnsignedLong(Bukkit.getCurrentTick());
            CoordinatedTask added;
            while ((added = incoming.poll()) != null) {
                if (!added.isCancelled()) {
                    scheduled.add(added);
                }
            }
            while (true) {
                CoordinatedTask task = scheduled.peek();
                if (task == null || task.dueTick() > currentTick) {
                    break;
                }
                scheduled.poll();
                if (task.isCancelled()) {
                    continue;
                }
                try {
                    task.runnable.run();
                } catch (Throwable throwable) {
                    task.cancel();
                    plugin.getLogger().log(Level.SEVERE,
                            "Cancelled a coordinated InteractionVisualizer task after an exception", throwable);
                    continue;
                }
                if (task.period > 0L && !task.isCancelled() && !closed) {
                    task.dueTick = currentTick + task.period;
                    scheduled.add(task);
                } else {
                    TASKS_BY_ID.remove(task.getTaskId(), task);
                }
            }
        }

        private void shutdown() {
            closed = true;
            driver.cancel();
            CoordinatedTask task;
            while ((task = incoming.poll()) != null) {
                task.cancel();
            }
            for (CoordinatedTask scheduledTask : scheduled) {
                scheduledTask.cancel();
            }
            scheduled.clear();
        }

        private int retainedTaskCount() {
            return incoming.size() + scheduled.size();
        }
    }

    private static final class CoordinatedTask implements BukkitTask {

        private final Coordinator coordinator;
        private final int taskId;
        private final Runnable runnable;
        private final long period;
        private volatile long dueTick;
        private volatile boolean cancelled;

        private CoordinatedTask(Coordinator coordinator, int taskId, Runnable runnable,
                                long dueTick, long period) {
            this.coordinator = coordinator;
            this.taskId = taskId;
            this.runnable = runnable;
            this.dueTick = dueTick;
            this.period = period;
        }

        private long dueTick() {
            return dueTick;
        }

        @Override
        public int getTaskId() {
            return taskId;
        }

        @Override
        public Plugin getOwner() {
            return coordinator.plugin;
        }

        @Override
        public boolean isSync() {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void cancel() {
            if (!cancelled) {
                cancelled = true;
                TASKS_BY_ID.remove(taskId, this);
            }
        }
    }
}
