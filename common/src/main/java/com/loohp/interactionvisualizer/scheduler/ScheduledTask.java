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

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;

/**
 * A small, dependency-free wrapper around Paper's Bukkit scheduler task.
 */
public final class ScheduledTask {

    private final BukkitTask task;

    ScheduledTask(BukkitTask task) {
        this.task = Objects.requireNonNull(task, "task");
    }

    public boolean isCancelled() {
        return task.isCancelled();
    }

    public void cancel() {
        task.cancel();
    }

    public Plugin getOwner() {
        return task.getOwner();
    }

    public int getTaskId() {
        return task.getTaskId();
    }

    public BukkitTask getBukkitTask() {
        return task;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ScheduledTask that && task.equals(that.task);
    }

    @Override
    public int hashCode() {
        return task.hashCode();
    }

}
