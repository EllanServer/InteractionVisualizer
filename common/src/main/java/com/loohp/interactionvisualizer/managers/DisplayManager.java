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
import com.loohp.interactionvisualizer.integration.packet.ClientPickupAnimationBridge;
import com.loohp.interactionvisualizer.utils.DisplayTransformFactory;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.heart.SparrowHeart;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Main-thread Paper entity renderer.
 *
 * <p>Paper's native tracker owns range and chunk lifecycle. Logical items use
 * empty display anchors plus Sparrow's client-side {@code ITEM} packets, so
 * the client keeps vanilla bob/spin rendering without server item physics.</p>
 */
public final class DisplayManager implements Listener {

    private static final double ITEM_ANIMATION_EPSILON = 1.0E-12;
    private static final double ITEM_GRAVITY_PER_TICK = 0.04;
    private static final double ITEM_HORIZONTAL_DRAG_PER_TICK = 0.98F;
    private static final double ITEM_VERTICAL_DRAG_PER_TICK = 0.98;
    private static final double ITEM_VOID_MARGIN = 64.0;
    private static final int VIRTUAL_REMOVE_BATCH_SIZE = 256;

    public static final Map<VisualizerEntity, Collection<Player>> active = new ConcurrentHashMap<>();
    public static final Map<Player, Set<VisualizerEntity>> playerStatus = new ConcurrentHashMap<>();

    private static final Map<VisualizerEntity, Integer> renderedRevision = new ConcurrentHashMap<>();
    private static final Map<VisualizerEntity, Set<UUID>> shownViewers = new ConcurrentHashMap<>();
    private static final Set<VisualizerEntity> scheduled = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, VisualizerEntity> logicalByActualUuid = new ConcurrentHashMap<>();
    private static final Map<VisualizerEntity, UUID> actualUuidByLogical = new ConcurrentHashMap<>();
    private static final Map<ChunkKey, Set<VisualizerEntity>> logicalsByChunk = new ConcurrentHashMap<>();
    private static final Map<VisualizerEntity, ChunkKey> chunkByLogical = new ConcurrentHashMap<>();
    private static final Map<Item, ItemAnimationState> itemAnimations = new ConcurrentHashMap<>();
    private static final Map<Item, Map<UUID, Integer>> virtualItemIds = new ConcurrentHashMap<>();
    private static final Map<Item, ItemStack> renderedItemStacks = new ConcurrentHashMap<>();
    private static boolean itemAnimationTickScheduled;

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
        // Fail during enable instead of waiting for the first player tracker event
        // if the shaded packet adapter does not match this Paper runtime.
        SparrowHeart.getInstance();
        if (!ClientPickupAnimationBridge.initialize()) {
            plugin().getLogger().log(Level.WARNING,
                    "Vanilla client pickup animations are unavailable; affected items will be removed immediately",
                    ClientPickupAnimationBridge.initializationFailure());
        }
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
            itemAnimations.clear();
            virtualItemIds.clear();
            renderedItemStacks.clear();
            itemAnimationTickScheduled = false;
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

    /**
     * Hands a virtual item to Minecraft's native three-tick client pickup animation.
     * The client follows the collector and removes the complete displayed stack.
     */
    public static void collectItem(Item entity, Player collector) {
        if (entity == null || collector == null) {
            return;
        }
        runSync(() -> collectVirtualItem(entity, collector));
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
            List<Integer> virtualItemIdsToRemove = new ArrayList<>();
            for (Map.Entry<VisualizerEntity, Set<UUID>> entry : shownViewers.entrySet()) {
                if (entry.getValue().remove(player.getUniqueId())) {
                    if (entry.getKey() instanceof Item item) {
                        Integer id = forgetVirtualItem(item, player.getUniqueId());
                        if (id != null) {
                            virtualItemIdsToRemove.add(id);
                        }
                    }
                    entry.getKey().getBukkitEntity().ifPresent(actual -> {
                        PerformanceMetrics.bukkitHide();
                        player.hideEntity(plugin(), actual);
                    });
                }
            }
            removeVirtualItems(player, virtualItemIdsToRemove);
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
        PerformanceMetrics.displaySync();
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
            PerformanceMetrics.bukkitEntitySpawn();
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
            PerformanceMetrics.bukkitEntityTeleport();
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
        PerformanceMetrics.itemSync();
        org.bukkit.entity.Entity current = logical.getBukkitEntity().orElse(null);
        if (current != null && (!current.getWorld().equals(logical.getWorld())
                || !(current instanceof ItemDisplay))) {
            discardActual(logical, current);
            current = null;
        }

        ItemDisplay actual;
        if (current instanceof ItemDisplay display) {
            actual = display;
        } else {
            clearViewerTracking(logical);
            actual = logical.getWorld().spawn(logical.getLocation(), ItemDisplay.class,
                    DisplayManager::initializeDisplay);
            PerformanceMetrics.bukkitEntitySpawn();
            logical.bind(actual);
            trackActual(logical, actual);
        }

        ItemStack itemStack = logical.getItemStack();
        ItemStack previousItemStack = renderedItemStacks.put(logical, itemStack.clone());
        boolean itemChanged = previousItemStack == null || !previousItemStack.equals(itemStack);

        // The Paper entity is only an invisible tracker/name anchor. Rendering
        // the stack here would duplicate Sparrow's client-side ITEM entity.
        actual.setItemStack(ItemStack.empty());
        actual.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
        actual.setBillboard(Display.Billboard.FIXED);
        actual.setViewRange(1.0F);
        actual.setShadowRadius(0.0F);
        actual.setShadowStrength(0.0F);
        actual.setInterpolationDelay(0);
        actual.setInterpolationDuration(0);
        actual.setTeleportDuration(0);
        applyItemBase(actual, logical);

        Vector velocity = logical.getVelocity();
        boolean animated = requiresItemAnimation(logical.hasGravity(), velocity);
        if (animated) {
            ItemAnimationState previous = itemAnimations.get(logical);
            Location position = previous == null ? actual.getLocation() : previous.position.clone();
            itemAnimations.put(logical, new ItemAnimationState(velocity, logical.hasGravity(), position,
                    useStaticAnchorForAnimation(InteractionVisualizer.staticVirtualItemAnchorsDuringAnimation,
                            logical.isCustomNameVisible())));
            scheduleItemAnimationTick();
        } else {
            itemAnimations.remove(logical);
        }

        if (itemChanged) {
            respawnVirtualItems(logical);
        } else {
            synchronizeVirtualItemMotion(logical, actual.getLocation());
        }
    }

    private static void applyItemBase(org.bukkit.entity.Entity actual, Item logical) {
        actual.setGlowing(logical.isGlowing());
        actual.customName(logical.getCustomName());
        actual.setCustomNameVisible(logical.isCustomNameVisible());
        if (!actual.getWorld().equals(logical.getWorld())
                || actual.getLocation().distanceSquared(logical.getLocation()) > 1.0E-8) {
            PerformanceMetrics.bukkitEntityTeleport();
            actual.teleport(logical.getLocation());
        }
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

    static boolean requiresItemAnimation(boolean gravity, Vector velocity) {
        return gravity || velocity.lengthSquared() > ITEM_ANIMATION_EPSILON;
    }

    static boolean useStaticAnchorForAnimation(boolean configured, boolean customNameVisible) {
        return configured && !customNameVisible;
    }

    private static void scheduleItemAnimationTick() {
        if (itemAnimationTickScheduled || itemAnimations.isEmpty()
                || plugin() == null || !plugin().isEnabled()) {
            return;
        }
        itemAnimationTickScheduled = true;
        Bukkit.getScheduler().runTaskLater(plugin(), () -> {
            try {
                tickItemAnimations();
            } finally {
                itemAnimationTickScheduled = false;
                scheduleItemAnimationTick();
            }
        }, 1L);
    }

    private static void tickItemAnimations() {
        long started = PerformanceMetrics.isCollecting() ? System.nanoTime() : 0L;
        try {
            for (Map.Entry<Item, ItemAnimationState> entry : itemAnimations.entrySet()) {
                Item logical = entry.getKey();
                ItemAnimationState animation = entry.getValue();
                try {
                    tickItemAnimation(logical, animation);
                } catch (RuntimeException exception) {
                    itemAnimations.remove(logical, animation);
                    remove(null, logical, true);
                    Plugin plugin = plugin();
                    if (plugin != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "Stopped an invalid visual item animation at " + logical.getLocation(), exception);
                    }
                }
            }
        } finally {
            if (started != 0L) {
                PerformanceMetrics.itemAnimationNanos(System.nanoTime() - started);
            }
        }
    }

    private static void tickItemAnimation(Item logical, ItemAnimationState animation) {
        org.bukkit.entity.Entity entity = logical.getBukkitEntity().orElse(null);
        if (!active.containsKey(logical) || !(entity instanceof ItemDisplay actual)) {
            itemAnimations.remove(logical, animation);
            return;
        }

        Vector movement = itemMovementForTick(animation.gravity, animation.velocity);
        Location destination = animation.position.clone().add(movement);
        destination.checkFinite();
        World world = destination.getWorld();
        if (destination.getY() < world.getMinHeight() - ITEM_VOID_MARGIN
                || !world.isChunkLoaded(destination.getBlockX() >> 4, destination.getBlockZ() >> 4)) {
            remove(null, logical, true);
            return;
        }

        animation.velocity = itemVelocityAfterMovement(movement);
        animation.position = destination.clone();
        if (!animation.staticAnchor) {
            PerformanceMetrics.bukkitEntityTeleport();
            if (!actual.teleport(destination)) {
                remove(null, logical, true);
                return;
            }
        }
        if (animation.gravity) {
            // Heart's fake item is no-gravity. Absolute correction preserves the
            // vanilla gravity trajectory; no-gravity motion remains client-run.
            synchronizeVirtualItemMotion(logical, destination);
        }
        if (!animation.gravity && animation.velocity.lengthSquared() <= ITEM_ANIMATION_EPSILON) {
            itemAnimations.remove(logical, animation);
            if (animation.staticAnchor) {
                PerformanceMetrics.bukkitEntityTeleport();
                if (!actual.teleport(destination)) {
                    remove(null, logical, true);
                    return;
                }
            }
            synchronizeVirtualItemMotion(logical, destination);
        }
    }

    /** Mirrors the open-air ItemEntity tick order without its collision push-out path. */
    static Vector itemMovementForTick(boolean gravity, Vector velocity) {
        Vector movement = velocity.clone();
        if (gravity) {
            movement.setY(movement.getY() - ITEM_GRAVITY_PER_TICK);
        }
        return movement;
    }

    /** ItemEntity deliberately uses float precision horizontally and double precision vertically. */
    static Vector itemVelocityAfterMovement(Vector movement) {
        return new Vector(
                movement.getX() * ITEM_HORIZONTAL_DRAG_PER_TICK,
                movement.getY() * ITEM_VERTICAL_DRAG_PER_TICK,
                movement.getZ() * ITEM_HORIZONTAL_DRAG_PER_TICK);
    }

    private static void spawnVirtualItem(Item logical, Player player) {
        org.bukkit.entity.Entity anchor = logical.getBukkitEntity().orElse(null);
        Set<UUID> shown = shownViewers.get(logical);
        if (!(anchor instanceof ItemDisplay) || !active.containsKey(logical)
                || shown == null || !shown.contains(player.getUniqueId())
                || !player.isOnline() || !player.getWorld().equals(anchor.getWorld())) {
            return;
        }

        Map<UUID, Integer> ids = virtualItemIds.computeIfAbsent(logical, ignored -> new ConcurrentHashMap<>());
        UUID viewer = player.getUniqueId();
        if (ids.containsKey(viewer)) {
            return;
        }

        Integer spawnedId = null;
        try {
            spawnedId = SparrowHeart.getInstance().dropFakeItem(
                    player, logical.getItemStack(), anchor.getLocation());
            PerformanceMetrics.virtualSpawnBundle();
            ids.put(viewer, spawnedId);
            Vector motion = nextVirtualItemMotion(logical);
            if (motion.lengthSquared() > ITEM_ANIMATION_EPSILON) {
                SparrowHeart.getInstance().sendClientSideEntityMotion(player, motion, spawnedId);
                PerformanceMetrics.virtualMotionBundle();
            }
        } catch (RuntimeException exception) {
            if (spawnedId != null) {
                ids.remove(viewer, spawnedId);
                try {
                    SparrowHeart.getInstance().removeClientSideEntity(player, spawnedId);
                    PerformanceMetrics.virtualRemovePacket();
                } catch (RuntimeException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
            }
            if (ids.isEmpty()) {
                virtualItemIds.remove(logical, ids);
            }
            logVirtualItemFailure("spawn", logical, exception);
        }
    }

    private static Vector nextVirtualItemMotion(Item logical) {
        ItemAnimationState animation = itemAnimations.get(logical);
        return animation == null
                ? new Vector()
                : itemMovementForTick(animation.gravity, animation.velocity);
    }

    private static void synchronizeVirtualItemMotion(Item logical, Location location) {
        Map<UUID, Integer> ids = virtualItemIds.get(logical);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Vector motion = nextVirtualItemMotion(logical);
        for (Map.Entry<UUID, Integer> entry : ids.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || !player.getWorld().equals(location.getWorld())) {
                removeVirtualItem(logical, entry.getKey(), player);
                continue;
            }
            try {
                // The 26.1/26.2 teleport packet includes delta movement, so one
                // packet both corrects the absolute position and primes the next tick.
                SparrowHeart.getInstance().sendClientSideTeleportEntity(
                        player, location, motion, false, entry.getValue());
                PerformanceMetrics.virtualTeleportBundle();
            } catch (RuntimeException exception) {
                removeVirtualItem(logical, entry.getKey(), player);
                logVirtualItemFailure("move", logical, exception);
            }
        }
    }

    private static void respawnVirtualItems(Item logical) {
        Map<UUID, Integer> ids = virtualItemIds.get(logical);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<UUID> viewers = new HashSet<>(ids.keySet());
        for (UUID viewer : viewers) {
            Player player = Bukkit.getPlayer(viewer);
            removeVirtualItem(logical, viewer, player);
            if (player != null) {
                spawnVirtualItem(logical, player);
            }
        }
    }

    private static void collectVirtualItem(Item logical, Player collector) {
        try {
            Map<UUID, Integer> ids = virtualItemIds.get(logical);
            int amount = Math.max(1, logical.getItemStack().getAmount());
            boolean pickupPacketAvailable = ClientPickupAnimationBridge.initialize();
            if (ids != null) {
                for (UUID viewerId : new HashSet<>(ids.keySet())) {
                    Integer itemEntityId = forgetVirtualItem(logical, viewerId);
                    if (itemEntityId == null) {
                        continue;
                    }
                    Player viewer = Bukkit.getPlayer(viewerId);
                    boolean canReceive = viewer != null && viewer.isOnline() && collector.isOnline()
                            && viewer.getWorld().equals(collector.getWorld());
                    if (!canReceive || !pickupPacketAvailable) {
                        removeClaimedVirtualItem(logical, viewer, itemEntityId);
                        continue;
                    }

                    try {
                        // Vanilla broadcasts take to the source item's trackers and
                        // lets the client resolve the collector (including its local
                        // player fallback), so do not add a target-tracking filter.
                        ClientPickupAnimationBridge.send(viewer, itemEntityId, collector, amount);
                        PerformanceMetrics.virtualPickupPacket();
                    } catch (RuntimeException | LinkageError exception) {
                        removeClaimedVirtualItem(logical, viewer, itemEntityId);
                        logVirtualItemFailure("collect", logical, exception);
                    }
                }
            }
        } finally {
            remove(null, logical, true);
        }
    }

    private static Integer forgetVirtualItem(Item logical, UUID viewer) {
        Map<UUID, Integer> ids = virtualItemIds.get(logical);
        if (ids == null) {
            return null;
        }
        Integer id = ids.remove(viewer);
        if (ids.isEmpty()) {
            virtualItemIds.remove(logical, ids);
        }
        return id;
    }

    private static void removeVirtualItem(Item logical, UUID viewer, Player player) {
        Integer id = forgetVirtualItem(logical, viewer);
        if (id != null) {
            removeClaimedVirtualItem(logical, player, id);
        }
    }

    private static void removeClaimedVirtualItem(Item logical, Player player, int id) {
        if (player != null && player.isOnline()) {
            try {
                SparrowHeart.getInstance().removeClientSideEntity(player, id);
                PerformanceMetrics.virtualRemovePacket();
            } catch (RuntimeException exception) {
                logVirtualItemFailure("remove", logical, exception);
            }
        }
    }

    private static void removeVirtualItems(Player player, List<Integer> ids) {
        if (ids.isEmpty() || player == null || !player.isOnline()) {
            return;
        }
        for (int start = 0; start < ids.size(); start += VIRTUAL_REMOVE_BATCH_SIZE) {
            int end = Math.min(ids.size(), start + VIRTUAL_REMOVE_BATCH_SIZE);
            int[] batch = new int[end - start];
            for (int index = start; index < end; index++) {
                batch[index - start] = ids.get(index);
            }
            try {
                SparrowHeart.getInstance().removeClientSideEntity(player, batch);
                PerformanceMetrics.virtualRemovePacket();
            } catch (RuntimeException exception) {
                Plugin plugin = plugin();
                if (plugin != null) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to batch-remove " + batch.length + " Sparrow virtual items", exception);
                }
            }
        }
    }

    private static void destroyVirtualItems(Item logical) {
        Map<UUID, Integer> ids = virtualItemIds.remove(logical);
        if (ids == null) {
            return;
        }
        for (Map.Entry<UUID, Integer> entry : ids.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                try {
                    SparrowHeart.getInstance().removeClientSideEntity(player, entry.getValue());
                    PerformanceMetrics.virtualRemovePacket();
                } catch (RuntimeException exception) {
                    logVirtualItemFailure("remove", logical, exception);
                }
            }
        }
    }

    private static void logVirtualItemFailure(String operation, Item logical, Throwable exception) {
        Plugin plugin = plugin();
        if (plugin != null) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to " + operation + " a Sparrow virtual item at " + logical.getLocation(), exception);
        }
    }

    private static void discardActual(VisualizerEntity logical, org.bukkit.entity.Entity actual) {
        if (logical instanceof Item item) {
            itemAnimations.remove(item);
            renderedItemStacks.remove(item);
            destroyVirtualItems(item);
        }
        untrackActual(logical, actual);
        clearViewerTracking(logical, actual);
        PerformanceMetrics.bukkitEntityRemove();
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
        if (logical instanceof Item item) {
            destroyVirtualItems(item);
        }
        Set<UUID> shown = shownViewers.remove(logical);
        if (shown == null) {
            return;
        }
        for (UUID uuid : shown) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                if (actual != null) {
                    PerformanceMetrics.bukkitHide();
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
                            PerformanceMetrics.bukkitHide();
                            player.hideEntity(plugin(), entity);
                        }
                    }
                    PerformanceMetrics.bukkitEntityRemove();
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
                    if (logical instanceof Item item) {
                        removeVirtualItem(item, uuid, player);
                    }
                    PerformanceMetrics.bukkitHide();
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
                    PerformanceMetrics.bukkitShow();
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
            if (logical instanceof Item item) {
                itemAnimations.remove(item);
                renderedItemStacks.remove(item);
            }
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
                        if (logical instanceof Item item) {
                            removeVirtualItem(item, player.getUniqueId(), player);
                        }
                        PerformanceMetrics.bukkitHide();
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackEntity(PlayerTrackEntityEvent event) {
        // A successful Track event is the source of truth. Paper can leave
        // isTrackedBy() true after another listener cancels this event.
        VisualizerEntity logical = logicalByActualUuid.get(event.getEntity().getUniqueId());
        if (logical instanceof Item item) {
            spawnVirtualItem(item, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUntrackEntity(PlayerUntrackEntityEvent event) {
        VisualizerEntity logical = logicalByActualUuid.get(event.getEntity().getUniqueId());
        if (logical instanceof Item item) {
            removeVirtualItem(item, event.getPlayer().getUniqueId(), event.getPlayer());
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
        for (Map.Entry<VisualizerEntity, Set<UUID>> entry : shownViewers.entrySet()) {
            entry.getValue().remove(uuid);
            if (entry.getKey() instanceof Item item) {
                // The connection is closing; discard bookkeeping without
                // sending a remove packet that cannot be observed.
                forgetVirtualItem(item, uuid);
            }
        }
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
        boolean movingItem = logical instanceof Item item && itemAnimations.remove(item) != null;
        logical.unbind();
        clearViewerTracking(logical);
        if (event.getCause() == EntityRemoveEvent.Cause.UNLOAD && movingItem) {
            active.remove(logical);
            renderedRevision.remove(logical);
            unindex(logical);
            renderedItemStacks.remove((Item) logical);
            return;
        }
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

    private static final class ItemAnimationState {

        private Vector velocity;
        private final boolean gravity;
        private Location position;
        private final boolean staticAnchor;

        private ItemAnimationState(Vector velocity, boolean gravity, Location position, boolean staticAnchor) {
            this.velocity = velocity.clone();
            this.gravity = gravity;
            this.position = position.clone();
            this.staticAnchor = staticAnchor;
        }
    }

    private record ChunkKey(UUID world, int x, int z) {
    }
}
