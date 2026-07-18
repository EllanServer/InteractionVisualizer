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
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * Paper-only scheduling facade. Location and entity overloads deliberately
 * use the global main-thread scheduler because this build does not advertise
 * Folia support.
 */
public final class Scheduler {

    private Scheduler() {
    }

    public static void executeOrScheduleSync(Plugin plugin, Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            runTask(plugin, runnable);
        }
    }

    public static ScheduledTask runTask(Plugin plugin, Runnable runnable, Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return runTask(plugin, runnable);
    }

    public static ScheduledTask runTaskLater(Plugin plugin, Runnable runnable, long delay, Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return runTaskLater(plugin, runnable, delay);
    }

    public static ScheduledTask runTaskTimer(Plugin plugin, Runnable runnable, long delay, long period, Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return runTaskTimer(plugin, runnable, delay, period);
    }

    public static ScheduledTask runTask(Plugin plugin, Runnable runnable, Location location) {
        Objects.requireNonNull(location, "location");
        return runTask(plugin, runnable);
    }

    public static ScheduledTask runTaskLater(Plugin plugin, Runnable runnable, long delay, Location location) {
        Objects.requireNonNull(location, "location");
        return runTaskLater(plugin, runnable, delay);
    }

    public static ScheduledTask runTaskTimer(Plugin plugin, Runnable runnable, long delay, long period, Location location) {
        Objects.requireNonNull(location, "location");
        return runTaskTimer(plugin, runnable, delay, period);
    }

    public static ScheduledTask runTask(Plugin plugin, Runnable runnable) {
        return MainThreadTaskCoordinator.schedule(plugin, runnable, 1L, 0L);
    }

    public static ScheduledTask runTaskLater(Plugin plugin, Runnable runnable, long delay) {
        return MainThreadTaskCoordinator.schedule(plugin, runnable, delay, 0L);
    }

    public static ScheduledTask runTaskTimer(Plugin plugin, Runnable runnable, long delay, long period) {
        return MainThreadTaskCoordinator.schedule(plugin, runnable, delay, period);
    }

    public static void cancelTask(int taskId) {
        if (!MainThreadTaskCoordinator.cancel(taskId)) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    public static void shutdown(Plugin plugin) {
        MainThreadTaskCoordinator.shutdown(plugin);
    }

    public static int retainedTaskCount(Plugin plugin) {
        return MainThreadTaskCoordinator.retainedTaskCount(plugin);
    }

}
