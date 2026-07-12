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
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
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
    private static final Map<UUID, Set<Item>> scenes = new HashMap<>();

    private PerformanceScene() {
    }

    public static int spawn(Player owner, boolean moving, int requestedCount, long requestedLifetimeTicks) {
        int count = Math.max(1, Math.min(MAX_ITEMS, requestedCount));
        long lifetimeTicks = Math.max(1L, Math.min(MAX_LIFETIME_TICKS, requestedLifetimeTicks));
        clear(owner);

        Collection<Player> viewers = new ArrayList<>(Bukkit.getOnlinePlayers());
        Location center = owner.getLocation().add(0.0D, 1.5D, 0.0D);
        int width = (int) Math.ceil(Math.sqrt(count));
        Set<Item> items = new HashSet<>(count);

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

        scenes.put(owner.getUniqueId(), items);
        Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> remove(items), lifetimeTicks, owner.getLocation());
        return count;
    }

    public static void clear(Player owner) {
        Set<Item> previous = scenes.remove(owner.getUniqueId());
        if (previous != null) {
            remove(previous);
        }
    }

    private static void remove(Set<Item> items) {
        for (Item item : items) {
            DisplayManager.removeItem(null, item, true, false);
        }
        scenes.values().removeIf(current -> current == items);
    }
}
