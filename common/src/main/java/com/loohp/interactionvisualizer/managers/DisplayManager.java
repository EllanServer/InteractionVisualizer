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
 */

package com.loohp.interactionvisualizer.managers;

import com.loohp.interactionvisualizer.InteractionVisualizer;
import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.entityholders.ItemFrame;
import com.loohp.interactionvisualizer.entityholders.VisualizerEntity;
import com.loohp.interactionvisualizer.utils.DisplayTransformFactory;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main-thread Paper entity renderer.
 *
 * <p>Paper's native tracker and Display interpolation replace the former raw
 * packet layer, 5 ms entity scans, player/entity cartesian scans, and
 * per-viewer hologram teleport loop.</p>
 */
public final class DisplayManager implements Listener {

    public static final Map<VisualizerEntity, Collection<Player>> active = new ConcurrentHashMap<>();
    public static final Map<Player, Set<VisualizerEntity>> playerStatus = new ConcurrentHashMap<>();

    private static final Map<VisualizerEntity, Integer> renderedRevision = new ConcurrentHashMap<>();
    private static final Map<VisualizerEntity, Set<UUID>> shownViewers = new ConcurrentHashMap<>();
    private static final Set<VisualizerEntity> scheduled = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, VisualizerEntity> logicalByActualUuid = new ConcurrentHashMap<>();
    private static final Map<VisualizerEntity, UUID> actualUuidByLogical = new ConcurrentHashMap<>();
    private static final Map<ChunkKey, Set<VisualizerEntity>> logicalsByChunk = new ConcurrentHashMap<>();
    private static final Map<VisualizerEntity, ChunkKey> chunkByLogical = new ConcurrentHashMap<>();

    public DisplayManager() {
    }

    private static Plugin plugin() {
        return InteractionVisualizer.plugin;
    }

    private static NamespacedKey ownerKey() {
        return new NamespacedKey(plugin(), "visual_entity");
    }

    /** Clean up displays left by an interrupted reload; no recurring scan is started. */
    public static void run() {
        runSync(() -> removeOwnedEntities(false));
    }

    /** One-shot liveness and viewer reconciliation, used after configuration reloads. */
    public static void update() {
        runSync(() -> {
            for (VisualizerEntity logical : new HashSet<>(active.keySet())) {
                index(logical);
                if (logical.getBukkitEntity().isEmpty() && requiresActualEntity(logical)) {
                    Location location = logical.getLocation();
                    if (location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                        schedule(logical, true);
                    }
                } else {
                    reconcileViewers(logical);
                }
            }
        });
    }

    /** Billboard rotation is handled client-side by TextDisplay. */
    public static void dynamicEntity() {
    }

    public static void shutdown() {
        Runnable cleanup = () -> {
            for (VisualizerEntity logical : new HashSet<>(active.keySet())) {
                org.bukkit.entity.Entity actual = logical.getBukkitEntity().orElse(null);
                if (actual != null) {
                    discardActual(logical, actual);
                } else {
                    logical.unbind();
                    clearViewerTracking(logical);
                }
            }
            active.clear();
            renderedRevision.clear();
            shownViewers.clear();
            scheduled.clear();
            logicalByActualUuid.clear();
            actualUuidByLogical.clear();
            logicalsByChunk.clear();
            chunkByLogical.clear();
            playerStatus.clear();
            removeOwnedEntities(true);
        };
        if (Bukkit.isPrimaryThread()) {
            cleanup.run();
        } else {
            runSync(cleanup);
        }
    }

    public static void sendHandMovement(Collection<Player> viewers, Player player) {
        runSync(player::swingMainHand);
    }

    public static void spawnDisplay(Collection<Player> players, DisplayEntity entity) {
        active.putIfAbsent(entity, players);
        schedule(entity, true);
    }

    public static void updateDisplay(DisplayEntity entity) {
        updateDisplay(active.get(entity), entity, false);
    }

    public static void updateDisplay(Collection<Player> players, DisplayEntity entity) {
        updateDisplay(players, entity, false);
    }

    public static void updateDisplay(Collection<Player> players, DisplayEntity entity, boolean bypassCache) {
        if (players != null) {
            active.putIfAbsent(entity, players);
        }
        schedule(entity, bypassCache);
    }

    public static void removeDisplay(Collection<Player> players, DisplayEntity entity,
                                     boolean removeFromActive, boolean bypassFilter) {
        remove(players, entity, removeFromActive);
    }

    public static void removeDisplay(Collection<Player> players, DisplayEntity entity) {
        remove(players, entity, true);
    }

    public static void sendItemSpawn(Collection<Player> players, Item entity) {
        active.putIfAbsent(entity, players);
        schedule(entity, true);
    }

    public static void updateItem(Item entity) {
        updateItem(active.get(entity), entity, false);
    }

    public static void updateItem(Collection<Player> players, Item entity) {
        updateItem(players, entity, false);
    }

    public static void updateItem(Collection<Player> players, Item entity, boolean bypassCache) {
        if (players != null) {
            active.putIfAbsent(entity, players);
        }
        schedule(entity, bypassCache);
    }

    public static void updateItemAsync(Item entity, boolean bypassCache) {
        updateItem(entity);
    }

    public static void updateItemAsync(Collection<Player> players, Item entity, boolean bypassCache) {
        updateItem(players, entity, bypassCache);
    }

    public static void removeItem(Collection<Player> players, Item entity,
                                  boolean removeFromActive, boolean bypassFilter) {
        remove(players, entity, removeFromActive);
    }

    public static void removeItem(Collection<Player> players, Item entity) {
        remove(players, entity, true);
    }

    public static void sendItemFrameSpawn(Collection<Player> players, ItemFrame entity) {
        active.putIfAbsent(entity, players);
        schedule(entity, true);
    }

    public static void updateItemFrame(ItemFrame entity) {
        updateItemFrame(active.get(entity), entity, false);
    }

    public static void updateItemFrame(Collection<Player> players, ItemFrame entity) {
        updateItemFrame(players, entity, false);
    }

    public static void updateItemFrame(Collection<Player> players, ItemFrame entity, boolean bypassCache) {
        if (players != null) {
            active.putIfAbsent(entity, players);
        }
        schedule(entity, bypassCache);
    }

    public static void removeItemFrame(Collection<Player> players, ItemFrame entity,
                                       boolean removeFromActive, boolean bypassFilter) {
        remove(players, entity, removeFromActive);
    }

    public static void removeItemFrame(Collection<Player> players, ItemFrame entity) {
        remove(players, entity, true);
    }

    public static void reset(Player player) {
        removeAll(player);
        Bukkit.getScheduler().runTask(plugin(), () -> sendPlayerPackets(player));
    }

    public static void removeAll(Player player) {
        runSync(() -> {
            for (Map.Entry<VisualizerEntity, Set<UUID>> entry : shownViewers.entrySet()) {
                if (entry.getValue().remove(player.getUniqueId())) {
                    entry.getKey().getBukkitEntity().ifPresent(actual -> player.hideEntity(plugin(), actual));
                }
            }
            playerStatus.put(player, ConcurrentHashMap.newKeySet());
        });
    }

    public static void sendPlayerPackets(Player player) {
        runSync(() -> {
            playerStatus.putIfAbsent(player, ConcurrentHashMap.newKeySet());
            for (VisualizerEntity logical : active.keySet()) {
                if (logical.getBukkitEntity().isPresent()) {
                    reconcileViewers(logical);
                } else if (requiresActualEntity(logical)) {
                    schedule(logical, true);
                }
            }
        });
    }

    private static void schedule(VisualizerEntity logical, boolean force) {
        if (logical == null || plugin() == null || !plugin().isEnabled()) {
            return;
        }
        if (scheduled.add(logical)) {
            runSync(() -> {
                try {
                    if (active.containsKey(logical)) {
                        index(logical);
                        int revision = logical.cacheCode();
                        if (force || (requiresActualEntity(logical) && logical.getBukkitEntity().isEmpty())
                                || renderedRevision.getOrDefault(logical, Integer.MIN_VALUE) != revision) {
                            sync(logical, revision);
                        }
                    }
                } finally {
                    scheduled.remove(logical);
                }
                if (active.containsKey(logical)
                        && renderedRevision.getOrDefault(logical, Integer.MIN_VALUE) != logical.cacheCode()) {
                    schedule(logical, false);
                }
            });
        }
    }

    private static void sync(VisualizerEntity logical, int revision) {
        if (logical instanceof DisplayEntity display) {
            syncDisplay(display);
        } else if (logical instanceof Item item) {
            syncItem(item);
        } else if (logical instanceof ItemFrame frame) {
            syncItemFrame(frame);
        }
        renderedRevision.put(logical, revision);
        reconcileViewers(logical);
    }

    private static void syncDisplay(DisplayEntity logical) {
        boolean text = logical.isTextDisplay();
        org.bukkit.entity.Entity current = logical.getBukkitEntity().orElse(null);
        if (current != null && (!current.getWorld().equals(logical.getWorld())
                || text != (current instanceof TextDisplay))) {
            discardActual(logical, current);
            current = null;
        }

        if (!shouldRender(logical)) {
            if (current != null) {
                discardActual(logical, current);
            } else {
                clearViewerTracking(logical);
            }
            return;
        }

        Display actual;
        if (current == null) {
            clearViewerTracking(logical);
            actual = text
                    ? logical.getWorld().spawn(logical.getLocation(), TextDisplay.class, DisplayManager::initializeDisplay)
                    : logical.getWorld().spawn(logical.getLocation(), org.bukkit.entity.ItemDisplay.class, DisplayManager::initializeDisplay);
            logical.bind(actual);
            trackActual(logical, actual);
        } else {
            actual = (Display) current;
        }

        applyBase(actual, logical);
        if (actual instanceof TextDisplay textDisplay) {
            textDisplay.text(logical.getCustomName() == null ? Component.empty() : logical.getCustomName());
            textDisplay.setLineWidth(200);
            textDisplay.setAlignment(TextDisplay.TextAlignment.CENTER);
            textDisplay.setDefaultBackground(false);
            textDisplay.setBackgroundColor(Color.fromARGB(0));
            textDisplay.setShadowed(true);
            textDisplay.setSeeThrough(false);
            textDisplay.setTransformationMatrix(DisplayTransformFactory.text(logical));
        } else {
            org.bukkit.entity.ItemDisplay itemDisplay = (org.bukkit.entity.ItemDisplay) actual;
            itemDisplay.setItemStack(logical.getDisplayItem());
            itemDisplay.setItemDisplayTransform(logical.getItemDisplayTransform());
            itemDisplay.setTransformationMatrix(DisplayTransformFactory.item(logical));
        }
    }

    private static void initializeDisplay(Display display) {
        initializeEntity(display);
        display.setShadowRadius(0.0F);
        display.setShadowStrength(0.0F);
        display.setInterpolationDelay(0);
    }

    private static void applyBase(Display actual, DisplayEntity logical) {
        Location target = logical.getLocation();
        if (!actual.getWorld().equals(target.getWorld()) || actual.getLocation().distanceSquared(target) > 1.0E-8
                || actual.getYaw() != target.getYaw() || actual.getPitch() != target.getPitch()) {
            actual.teleport(target);
        }
        actual.setSilent(logical.isSilent());
        actual.setInvulnerable(logical.isInvulnerable());
        actual.setBillboard(logical.getBillboard());
        actual.setViewRange(logical.getViewRange());
        actual.setInterpolationDuration(logical.getInterpolationDuration());
        actual.setTeleportDuration(logical.getTeleportDuration());
    }

    private static void syncItem(Item logical) {
        org.bukkit.entity.Entity current = logical.getBukkitEntity().orElse(null);
        if (current != null && !current.getWorld().equals(logical.getWorld())) {
            discardActual(logical, current);
            current = null;
        }
        org.bukkit.entity.Item actual;
        if (current instanceof org.bukkit.entity.Item item) {
            actual = item;
        } else {
            if (current != null) {
                discardActual(logical, current);
            }
            clearViewerTracking(logical);
            actual = logical.getWorld().dropItem(logical.getLocation(), logical.getItemStack(), item -> {
                initializeEntity(item);
                item.setCanMobPickup(false);
                item.setCanPlayerPickup(false);
                item.setUnlimitedLifetime(true);
                item.setWillAge(false);
            });
            logical.bind(actual);
            trackActual(logical, actual);
        }
        actual.setItemStack(logical.getItemStack());
        actual.setPickupDelay(Math.max(logical.getPickupDelay(), Integer.MAX_VALUE / 2));
        actual.setNoPhysics(false);
        actual.setGravity(logical.hasGravity());
        actual.setGlowing(logical.isGlowing());
        actual.customName(logical.getCustomName());
        actual.setCustomNameVisible(logical.isCustomNameVisible());
        if (!actual.getWorld().equals(logical.getWorld())
                || actual.getLocation().distanceSquared(logical.getLocation()) > 1.0E-8) {
            actual.teleport(logical.getLocation());
        }
        actual.setVelocity(logical.getVelocity());
    }

    private static void syncItemFrame(ItemFrame logical) {
        org.bukkit.entity.Entity current = logical.getBukkitEntity().orElse(null);
        if (current != null && !current.getWorld().equals(logical.getWorld())) {
            discardActual(logical, current);
            current = null;
        }
        if (!shouldRender(logical)) {
            if (current != null) {
                discardActual(logical, current);
            } else {
                clearViewerTracking(logical);
            }
            return;
        }
        org.bukkit.entity.ItemDisplay actual;
        if (current instanceof org.bukkit.entity.ItemDisplay display) {
            actual = display;
        } else {
            if (current != null) {
                discardActual(logical, current);
            }
            clearViewerTracking(logical);
            Location location = frameLocation(logical);
            actual = logical.getWorld().spawn(location, org.bukkit.entity.ItemDisplay.class, DisplayManager::initializeDisplay);
            logical.bind(actual);
            trackActual(logical, actual);
        }
        actual.teleport(frameLocation(logical));
        actual.setItemStack(logical.getItem());
        actual.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED);
        actual.setTransformationMatrix(DisplayTransformFactory.itemFrame(logical));
    }

    private static Location frameLocation(ItemFrame logical) {
        Location location = logical.getLocation();
        location.setYaw(logical.getYaw());
        location.setPitch(logical.getPitch());
        return location;
    }

    private static void initializeEntity(org.bukkit.entity.Entity entity) {
        entity.setPersistent(false);
        entity.setVisibleByDefault(false);
        entity.setGravity(false);
        entity.setInvulnerable(true);
        entity.setSilent(true);
        entity.setNoPhysics(true);
        entity.getPersistentDataContainer().set(ownerKey(), PersistentDataType.STRING, "interactionvisualizer");
    }

    private static boolean isEmpty(Component component) {
        return component == null || component.equals(Component.empty());
    }

    private static boolean requiresActualEntity(VisualizerEntity logical) {
        if (logical instanceof DisplayEntity display) {
            return shouldRender(display);
        }
        if (logical instanceof ItemFrame frame) {
            return shouldRender(frame);
        }
        return logical instanceof Item;
    }

    private static boolean shouldRender(DisplayEntity logical) {
        return logical.isTextDisplay() ? !isEmpty(logical.getCustomName()) : !logical.getDisplayItem().isEmpty();
    }

    private static boolean shouldRender(ItemFrame logical) {
        return !logical.getItem().isEmpty();
    }

    private static void discardActual(VisualizerEntity logical, org.bukkit.entity.Entity actual) {
        untrackActual(logical, actual);
        clearViewerTracking(logical, actual);
        actual.remove();
        logical.unbind();
    }

    private static void trackActual(VisualizerEntity logical, org.bukkit.entity.Entity actual) {
        UUID uuid = actual.getUniqueId();
        UUID previous = actualUuidByLogical.put(logical, uuid);
        if (previous != null && !previous.equals(uuid)) {
            logicalByActualUuid.remove(previous, logical);
        }
        logicalByActualUuid.put(uuid, logical);
    }

    private static void untrackActual(VisualizerEntity logical, org.bukkit.entity.Entity actual) {
        UUID uuid = actual.getUniqueId();
        logicalByActualUuid.remove(uuid, logical);
        actualUuidByLogical.remove(logical, uuid);
    }

    private static void untrackActual(VisualizerEntity logical) {
        UUID uuid = actualUuidByLogical.remove(logical);
        if (uuid != null) {
            logicalByActualUuid.remove(uuid, logical);
        }
    }

    private static void index(VisualizerEntity logical) {
        Location location = logical.getLocation();
        ChunkKey current = new ChunkKey(
                location.getWorld().getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        ChunkKey previous = chunkByLogical.put(logical, current);
        if (previous != null && !previous.equals(current)) {
            Set<VisualizerEntity> previousEntries = logicalsByChunk.get(previous);
            if (previousEntries != null) {
                previousEntries.remove(logical);
                if (previousEntries.isEmpty()) {
                    logicalsByChunk.remove(previous, previousEntries);
                }
            }
        }
        logicalsByChunk.computeIfAbsent(current, ignored -> ConcurrentHashMap.newKeySet()).add(logical);
    }

    private static void unindex(VisualizerEntity logical) {
        ChunkKey key = chunkByLogical.remove(logical);
        if (key == null) {
            return;
        }
        Set<VisualizerEntity> entries = logicalsByChunk.get(key);
        if (entries != null) {
            entries.remove(logical);
            if (entries.isEmpty()) {
                logicalsByChunk.remove(key, entries);
            }
        }
    }

    private static void clearViewerTracking(VisualizerEntity logical) {
        clearViewerTracking(logical, null);
    }

    private static void clearViewerTracking(VisualizerEntity logical, org.bukkit.entity.Entity actual) {
        Set<UUID> shown = shownViewers.remove(logical);
        if (shown == null) {
            return;
        }
        for (UUID uuid : shown) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                if (actual != null) {
                    player.hideEntity(plugin(), actual);
                }
                Set<VisualizerEntity> status = playerStatus.get(player);
                if (status != null) {
                    status.remove(logical);
                }
            }
        }
    }

    private static void removeOwnedEntities(boolean clearVisibilityOverrides) {
        NamespacedKey key = ownerKey();
        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(key, PersistentDataType.STRING)) {
                    if (clearVisibilityOverrides) {
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            player.hideEntity(plugin(), entity);
                        }
                    }
                    entity.remove();
                }
            }
        }
    }

    private static void reconcileViewers(VisualizerEntity logical) {
        org.bukkit.entity.Entity actual = logical.getBukkitEntity().orElse(null);
        Collection<Player> desiredPlayers = active.get(logical);
        if (actual == null || desiredPlayers == null) {
            return;
        }

        Set<UUID> desired = new HashSet<>();
        for (Player player : desiredPlayers) {
            if (player != null && player.isOnline() && player.getWorld().equals(actual.getWorld())) {
                desired.add(player.getUniqueId());
            }
        }

        Set<UUID> shown = shownViewers.computeIfAbsent(logical, ignored -> ConcurrentHashMap.newKeySet());
        for (UUID uuid : new HashSet<>(shown)) {
            if (!desired.contains(uuid)) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.hideEntity(plugin(), actual);
                    Set<VisualizerEntity> status = playerStatus.get(player);
                    if (status != null) {
                        status.remove(logical);
                    }
                }
                shown.remove(uuid);
            }
        }
        for (UUID uuid : desired) {
            if (shown.add(uuid)) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.showEntity(plugin(), actual);
                    playerStatus.computeIfAbsent(player, ignored -> ConcurrentHashMap.newKeySet()).add(logical);
                }
            }
        }
    }

    private static void remove(Collection<Player> players, VisualizerEntity logical, boolean removeFromActive) {
        if (removeFromActive) {
            active.remove(logical);
            renderedRevision.remove(logical);
            unindex(logical);
        }
        runSync(() -> {
            org.bukkit.entity.Entity actual = logical.getBukkitEntity().orElse(null);
            if (removeFromActive) {
                if (actual != null) {
                    discardActual(logical, actual);
                } else {
                    untrackActual(logical);
                    logical.unbind();
                    clearViewerTracking(logical);
                }
            } else if (actual != null && players != null) {
                Set<UUID> shown = shownViewers.get(logical);
                if (shown == null) {
                    return;
                }
                for (Player player : players) {
                    if (player != null && shown.remove(player.getUniqueId())) {
                        player.hideEntity(plugin(), actual);
                        Set<VisualizerEntity> status = playerStatus.get(player);
                        if (status != null) {
                            status.remove(logical);
                        }
                    }
                }
            }
        });
    }

    private static void runSync(Runnable task) {
        Plugin plugin = plugin();
        if (plugin == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin(), () -> sendPlayerPackets(event.getPlayer()));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTask(plugin(), () -> sendPlayerPackets(event.getPlayer()));
    }

    @EventHandler
    public void onLeave(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        shownViewers.values().forEach(viewers -> viewers.remove(uuid));
        playerStatus.remove(event.getPlayer());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        ChunkKey key = new ChunkKey(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ());
        Set<VisualizerEntity> logicals = logicalsByChunk.get(key);
        if (logicals != null) {
            for (VisualizerEntity logical : new HashSet<>(logicals)) {
                if (active.containsKey(logical) && logical.getBukkitEntity().isEmpty()) {
                    schedule(logical, true);
                }
            }
        }
    }

    @EventHandler
    public void onEntityRemove(EntityRemoveEvent event) {
        org.bukkit.entity.Entity actual = event.getEntity();
        VisualizerEntity logical = logicalByActualUuid.remove(actual.getUniqueId());
        if (logical == null) {
            return;
        }
        actualUuidByLogical.remove(logical, actual.getUniqueId());
        logical.unbind();
        clearViewerTracking(logical);
        if (event.getCause() != EntityRemoveEvent.Cause.UNLOAD && active.containsKey(logical)) {
            Bukkit.getScheduler().runTask(plugin(), () -> {
                Location location = logical.getLocation();
                if (active.containsKey(logical)
                        && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                    schedule(logical, true);
                }
            });
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        NamespacedKey key = ownerKey();
        if (event.getEntity().getPersistentDataContainer().has(key, PersistentDataType.STRING)
                || event.getTarget().getPersistentDataContainer().has(key, PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    private record ChunkKey(UUID world, int x, int z) {
    }
}
