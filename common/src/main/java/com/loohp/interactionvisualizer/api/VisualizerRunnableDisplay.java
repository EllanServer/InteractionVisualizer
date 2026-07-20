/*
 * This file is part of InteractionVisualizer.
 *
 * Copyright (C) 2025. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2025. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.loohp.interactionvisualizer.api;

import com.loohp.interactionvisualizer.managers.TaskManager;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;

import java.util.HashSet;
import java.util.Set;

/**
 * This class is used for Displays which are shown when something is active by itself like a furnace cooking
 */
public abstract class VisualizerRunnableDisplay implements VisualizerDisplay {

    /**
     * DO NOT CHANGE THESE FIELD
     */
    private Set<ScheduledTask> tasks = new HashSet<>();
    private boolean unregistered;

    /**
     * This method is used for cleaning up, return the ScheduledTask, return null to disable.
     */
    public abstract ScheduledTask gc();

    /**
     * This method is used for a runnable, return the ScheduledTask, return null to disable.
     */
    public abstract ScheduledTask run();

    /**
     * Register this custom display to InteractionVisualizer.
     */
    public final void register() {
        if (key().isNative()) {
            throw new IllegalStateException("EntryKey must not have the default interactionvisualizer namespace");
        }
        InteractionVisualizerAPI.getPreferenceManager().registerEntry(key());
        TaskManager.runnables.add(this);
        this.unregistered = false;
        this.tasks = new HashSet<>();
        ScheduledTask gc = gc();
        if (gc != null) {
            this.tasks.add(gc);
        }
        ScheduledTask run = run();
        if (run != null) {
            this.tasks.add(run);
        }
    }

    @Deprecated
    public final EntryKey registerNative() {
        TaskManager.runnables.add(this);
        this.unregistered = false;
        this.tasks = new HashSet<>();
        ScheduledTask gc = gc();
        if (gc != null) {
            this.tasks.add(gc);
        }
        ScheduledTask run = run();
        if (run != null) {
            this.tasks.add(run);
        }
        return key();
    }

    /**
     * Called once while this display is being unregistered, after the tasks
     * returned by {@link #gc()} and {@link #run()} have been cancelled.
     */
    protected void onUnregister() {
    }

    /**
     * Unregister this custom display to InteractionVisualizer.
     * You don't have to use this normally.
     */
    @Deprecated
    public final synchronized void unregister() {
        if (unregistered) {
            return;
        }
        unregistered = true;

        Throwable failure = null;
        Set<ScheduledTask> registeredTasks = tasks;
        tasks = new HashSet<>();
        for (ScheduledTask task : registeredTasks) {
            try {
                task.cancel();
            } catch (Throwable throwable) {
                failure = appendFailure(failure, throwable);
            }
        }
        try {
            onUnregister();
        } catch (Throwable throwable) {
            failure = appendFailure(failure, throwable);
        }
        try {
            TaskManager.runnables.removeIf(each -> each == this);
        } catch (Throwable throwable) {
            failure = appendFailure(failure, throwable);
        }
        rethrow(failure);
    }

    private static Throwable appendFailure(Throwable current, Throwable addition) {
        if (current == null) {
            return addition;
        }
        if (current != addition) {
            current.addSuppressed(addition);
        }
        return current;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure != null) {
            throw new RuntimeException(failure);
        }
    }

}
