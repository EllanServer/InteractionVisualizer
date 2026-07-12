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

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Runnable that owns exactly one scheduled task and can safely cancel itself.
 */
public abstract class ScheduledRunnable implements Runnable {

    private ScheduledTask task;

    public synchronized boolean isCancelled() {
        checkScheduled();
        return task.isCancelled();
    }

    public synchronized void cancel() {
        checkScheduled();
        task.cancel();
    }

    public synchronized ScheduledTask runTask(Plugin plugin, Entity entity) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTask(plugin, this, entity));
    }

    public synchronized ScheduledTask runTaskLater(Plugin plugin, long delay, Entity entity) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskLater(plugin, this, delay, entity));
    }

    public synchronized ScheduledTask runTaskTimer(Plugin plugin, long delay, long period, Entity entity) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskTimer(plugin, this, delay, period, entity));
    }

    public synchronized ScheduledTask runTask(Plugin plugin, Location location) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTask(plugin, this, location));
    }

    public synchronized ScheduledTask runTaskLater(Plugin plugin, long delay, Location location) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskLater(plugin, this, delay, location));
    }

    public synchronized ScheduledTask runTaskTimer(Plugin plugin, long delay, long period, Location location) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskTimer(plugin, this, delay, period, location));
    }

    public synchronized ScheduledTask runTask(Plugin plugin) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTask(plugin, this));
    }

    public synchronized ScheduledTask runTaskLater(Plugin plugin, long delay) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskLater(plugin, this, delay));
    }

    public synchronized ScheduledTask runTaskTimer(Plugin plugin, long delay, long period) {
        checkNotYetScheduled();
        return setupTask(Scheduler.runTaskTimer(plugin, this, delay, period));
    }

    private void checkScheduled() {
        if (task == null) {
            throw new IllegalStateException("Not scheduled yet");
        }
    }

    private void checkNotYetScheduled() {
        if (task != null) {
            throw new IllegalStateException("Already scheduled as " + task);
        }
    }

    private ScheduledTask setupTask(ScheduledTask scheduledTask) {
        task = scheduledTask;
        return scheduledTask;
    }

}
