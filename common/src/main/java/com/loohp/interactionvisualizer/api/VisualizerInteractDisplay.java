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
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;

import java.util.HashSet;
import java.util.Set;

/**
 * This class is used for Displays which are shown when a player interact with a certain interface
 */
public abstract class VisualizerInteractDisplay implements VisualizerDisplay {

    /**
     * DO NOT CHANGE THESE FIELD
     */
    private InventoryType[] types;
    private Set<ScheduledTask> tasks;

    /**
     * This method will be called whenever a player opens the InventoryType registered.
     */
    public abstract void process(Player player);

    /**
     * This method is used if you need a runnable, return the ScheduledTask, return null to disable.
     */
    public ScheduledTask run() {
        return null;
    }

    /**
     * <b>Use register(InventoryType) instead</b>
     */
    @Deprecated
    public final void register() {
        throw new UnsupportedOperationException("Use register(InventoryType) instead");
    }

    @Deprecated
    public final EntryKey registerNative() {
        throw new UnsupportedOperationException("Use register(InventoryType) instead");
    }

    /**
     * Register this custom display to InteractionVisualizer.
     */
    public final void register(InventoryType type) {
        register(new InventoryType[] {type});
    }

    /**
     * Register this custom display to InteractionVisualizer.
     */
    public final void register(InventoryType... types) {
        if (types.length == 0) {
            throw new IllegalStateException("Display must be for at least one type of inventory");
        }
        if (key().isNative()) {
            throw new IllegalStateException("EntryKey must not have the default interactionvisualizer namespace");
        }
        InteractionVisualizerAPI.getPreferenceManager().registerEntry(key());
        this.types = types;
        for (InventoryType type : types) {
            TaskManager.processes.get(type).add(this);
        }
        this.tasks = new HashSet<>();
        ScheduledTask run = run();
        if (run != null) {
            this.tasks.add(run);
        }
    }

    /**
     * <b>Only use if this is a native built-in display</b>
     */
    @Deprecated
    public final EntryKey registerNative(InventoryType... types) {
        if (types.length == 0) {
            throw new IllegalStateException("Display must be for at least one type of inventory");
        }
        this.types = types;
        for (InventoryType type : types) {
            TaskManager.processes.get(type).add(this);
        }
        this.tasks = new HashSet<>();
        ScheduledTask run = run();
        if (run != null) {
            this.tasks.add(run);
        }
        return key();
    }

    /**
     * Unregister this custom display to InteractionVisualizer.
     * You don't have to use this normally.
     */
    @Deprecated
    public final void unregister() {
        for (InventoryType type : types) {
            TaskManager.processes.get(type).remove(this);
        }
        this.tasks.forEach(each -> each.cancel());
    }

}
