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

package com.loohp.interactionvisualizer.debug;

import com.loohp.interactionvisualizer.InteractionVisualizer;
import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.entityholders.VisualizerEntity;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Permission-gated, disposable visual-item workload for live A/B profiling. */
public final class PerformanceScene {

    private static final int MAX_ITEMS = 8_192;
    private static final long MAX_LIFETIME_TICKS = 12_000L;
    private static final Map<UUID, Set<VisualizerEntity>> scenes = new HashMap<>();

    private PerformanceScene() {
    }

    public static int spawn(Player owner, boolean moving, int requestedCount, long requestedLifetimeTicks) {
        int count = Math.max(1, Math.min(MAX_ITEMS, requestedCount));
        long lifetimeTicks = Math.max(1L, Math.min(MAX_LIFETIME_TICKS, requestedLifetimeTicks));
        clear(owner);

        Collection<Player> viewers = new ArrayList<>(Bukkit.getOnlinePlayers());
        Location center = owner.getLocation().add(0.0D, 1.5D, 0.0D);
        int width = (int) Math.ceil(Math.sqrt(count));
        Set<VisualizerEntity> items = new HashSet<>(count);

        for (int index = 0; index < count; index++) {
            int xIndex = index % width;
            int zIndex = index / width;
            Location location = center.clone().add(
                    (xIndex - (width - 1) / 2.0D) * 0.36D,
                    (index % 5) * 0.05D,
                    (zIndex - (width - 1) / 2.0D) * 0.36D);
            Item item = new Item(location);
            item.setItemStack(new ItemStack(Material.STONE));
            if (moving) {
                double angle = index * 2.399963229728653D;
                double speed = 0.04D + (index % 7) * 0.005D;
                item.setVelocity(new Vector(Math.cos(angle) * speed, 0.11D + (index % 3) * 0.015D,
                        Math.sin(angle) * speed));
                item.setGravity(true);
            }
            items.add(item);
            DisplayManager.sendItemSpawn(viewers, item);
        }

        UUID ownerId = owner.getUniqueId();
        scenes.put(ownerId, items);
        Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> expire(ownerId, items),
                lifetimeTicks, owner.getLocation());
        return count;
    }

    /**
     * Creates a Paper-owned display workload so the generic visibility queue is
     * measured independently from Sparrow's packet-only item branch.
     */
    public static int spawnDisplay(Player owner, boolean text, int requestedCount,
                                   long requestedLifetimeTicks) {
        int count = Math.max(1, Math.min(MAX_ITEMS, requestedCount));
        long lifetimeTicks = Math.max(1L, Math.min(MAX_LIFETIME_TICKS, requestedLifetimeTicks));
        clear(owner);

        Collection<Player> viewers = new ArrayList<>(Bukkit.getOnlinePlayers());
        Location center = owner.getLocation().add(0.0D, 1.5D, 0.0D);
        int width = (int) Math.ceil(Math.sqrt(count));
        Set<VisualizerEntity> displays = new HashSet<>(count);

        for (int index = 0; index < count; index++) {
            int xIndex = index % width;
            int zIndex = index / width;
            Location location = center.clone().add(
                    (xIndex - (width - 1) / 2.0D) * 0.36D,
                    (index % 5) * 0.05D,
                    (zIndex - (width - 1) / 2.0D) * 0.36D);
            DisplayEntity display = new DisplayEntity(location);
            if (text) {
                display.setCustomName(Component.text("IV"));
                display.setCustomNameVisible(true);
            } else {
                display.setItemInMainHand(new ItemStack(Material.STONE));
            }
            displays.add(display);
            DisplayManager.spawnDisplay(viewers, display);
        }

        UUID ownerId = owner.getUniqueId();
        scenes.put(ownerId, displays);
        Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> expire(ownerId, displays),
                lifetimeTicks, owner.getLocation());
        return count;
    }

    public static void clear(Player owner) {
        clear(owner.getUniqueId());
    }

    /** Removes a scene even when its owner has disconnected. */
    public static void clear(UUID ownerId) {
        Set<VisualizerEntity> previous = scenes.remove(ownerId);
        if (previous != null) {
            removeEntities(previous);
        }
    }

    /** Deterministically removes every disposable benchmark scene. */
    public static void shutdown() {
        List<Set<VisualizerEntity>> remaining = new ArrayList<>(scenes.values());
        scenes.clear();
        for (Set<VisualizerEntity> entities : remaining) {
            removeEntities(entities);
        }
    }

    public static int retainedStateCount() {
        return scenes.size();
    }

    private static void expire(UUID ownerId, Set<VisualizerEntity> entities) {
        if (scenes.remove(ownerId, entities)) {
            removeEntities(entities);
        }
    }

    private static void removeEntities(Set<VisualizerEntity> entities) {
        for (VisualizerEntity entity : entities) {
            if (entity instanceof Item item) {
                DisplayManager.removeItem(null, item, true, false);
            } else if (entity instanceof DisplayEntity display) {
                DisplayManager.removeDisplay(null, display, true, false);
            }
        }
    }
}
