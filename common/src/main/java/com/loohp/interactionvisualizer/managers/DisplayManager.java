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
import com.loohp.interactionvisualizer.entityholders.BillboardDisplayEntity;
import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.DynamicVisualizerEntity.PathType;
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.entityholders.ItemFrame;
import com.loohp.interactionvisualizer.entityholders.VisualizerEntity;
import com.loohp.interactionvisualizer.integration.packet.ClientPickupAnimationBridge;
import com.loohp.interactionvisualizer.integration.packet.ClientTextDisplayBridge;
import com.loohp.interactionvisualizer.integration.CullingBounds;
import com.loohp.interactionvisualizer.integration.ViewerCullingManager;
import com.loohp.interactionvisualizer.objectholders.SynchronizedFilteredCollection;
import com.loohp.interactionvisualizer.utils.DisplayTransformFactory;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.heart.SparrowHeart;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Main-thread Paper entity renderer.
 *
 * <p>Paper's native tracker owns ordinary displays and animated/name-bearing
 * items. Legacy name tags and eligible static items instead follow each
 * player's sent-chunk lifecycle as packet-only entities, avoiding server
 * entity ticks while retaining the original per-viewer visual behavior.</p>
 */
public final class DisplayManager implements Listener {

    private static final double ITEM_ANIMATION_EPSILON = 1.0E-12;
    private static final double ITEM_GRAVITY_PER_TICK = 0.04;
    private static final double ITEM_HORIZONTAL_DRAG_PER_TICK = 0.98F;
    private static final double ITEM_VERTICAL_DRAG_PER_TICK = 0.98;
    private static final double ITEM_VOID_MARGIN = 64.0;
    private static final double DYNAMIC_POSITION_EPSILON = 1.0E-12;
    private static final int VIRTUAL_REMOVE_BATCH_SIZE = 256;

    public static final Map<VisualizerEntity, Collection<Player>> active = new ConcurrentHashMap<>();
    public static final Map<Player, Set<VisualizerEntity>> playerStatus = new ConcurrentHashMap<>();

    private static final Map<VisualizerEntity, Integer> renderedRevision = new ConcurrentHashMap<>();
    private static final Map<VisualizerEntity, Set<UUID>> shownViewers = new ConcurrentHashMap<>();
    private static final Set<VisualizerEntity> scheduled = ConcurrentHashMap.newKeySet();
    private static final Set<VisualizerEntity> forceScheduled = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, VisualizerEntity> logicalByActualUuid = new ConcurrentHashMap<>();
    private static final Map<VisualizerEntity, UUID> actualUuidByLogical = new ConcurrentHashMap<>();
    private static final Map<UUID, VisualizerEntity> logicalById = new ConcurrentHashMap<>();
    private static final Map<ChunkKey, Set<VisualizerEntity>> logicalsByChunk = new ConcurrentHashMap<>();
    private static final Map<VisualizerEntity, ChunkKey> chunkByLogical = new ConcurrentHashMap<>();
    private static final ViewerChunkIndex<UUID, ChunkKey> viewerChunks = new ViewerChunkIndex<>();
    private static final Map<UUID, Set<VisualizerEntity>> shownByViewer = new ConcurrentHashMap<>();
    private static final Map<Item, ItemAnimationState> itemAnimations = new ConcurrentHashMap<>();
    private static final Map<Item, Map<UUID, Integer>> virtualItemIds = new ConcurrentHashMap<>();
    private static final Map<Item, ItemStack> renderedItemStacks = new ConcurrentHashMap<>();
    private static final Map<Item, Location> renderedItemLocations = new ConcurrentHashMap<>();
    private static final Map<Item, PacketItemPosition> packetItemPositions = new ConcurrentHashMap<>();
    private static final Set<Item> packetOnlyItems = ConcurrentHashMap.newKeySet();
    private static final Set<Item> fixedItemDisplays = ConcurrentHashMap.newKeySet();
    private static final Map<DisplayEntity, VirtualTextState> virtualTextDisplays = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<BillboardDisplayEntity, DynamicViewerPosition>> dynamicViewerPositions = new ConcurrentHashMap<>();
    private static final Set<UUID> dirtyDynamicViewers = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, VisibilityShowQueue<VisualizerEntity>> visibilityShowQueues = new ConcurrentHashMap<>();
    private static final Map<VisualizerEntity, Set<UUID>> pendingViewersByLogical = new ConcurrentHashMap<>();
    private static final Set<UUID> pendingVisibilityViewers = ConcurrentHashMap.newKeySet();
    private static boolean itemAnimationTickScheduled;
    private static boolean dynamicViewerTickScheduled;
    private static boolean dynamicTeleportFailureLogged;
    private static boolean virtualTextAvailable;
    private static boolean visibilityTickScheduled;

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
        virtualTextAvailable = ClientTextDisplayBridge.initialize();
        if (!virtualTextAvailable) {
            throw new IllegalStateException(
                    "This Paper runtime cannot provide exact packet-only legacy text displays",
                    ClientTextDisplayBridge.initializationFailure());
        }
        runSync(() -> {
            removeOwnedEntities(false);
            for (Player player : Bukkit.getOnlinePlayers()) {
                seedSentChunks(player);
            }
        });
    }

    /** One-shot liveness and viewer reconciliation, used after configuration reloads. */
    public static void update() {
        runSync(() -> {
            // Active viewer collections are live filtered views. A reload can
            // therefore invalidate requests that have not reached shownViewers
            // yet; clear them once and rebuild from the new desired sets below.
            clearAllVisibilityShowQueues();
            for (VisualizerEntity logical : new HashSet<>(active.keySet())) {
                index(logical);
                if (logical instanceof Item item) {
                    // These experiment flags are not part of the logical entity's
                    // cache code. Force a sync only when a reload actually changes
                    // the representation; otherwise avoid a full item teleport burst.
                    ItemAnimationState animation = itemAnimations.get(item);
                    boolean desiredStaticAnchor = useStaticAnchorForAnimation(
                            InteractionVisualizer.staticVirtualItemAnchorsDuringAnimation,
                            item.isCustomNameVisible());
                    boolean packetOnly = qualifiesForPacketOnly(item);
                    boolean representationChanged = fixedItemDisplays.contains(item) != item.isFixedDisplay()
                            || packetOnlyItems.contains(item) != packetOnly
                            || animation != null && animation.staticAnchor != desiredStaticAnchor;
                    boolean missingRequiredAnchor = requiresActualEntity(item)
                            && item.getBukkitEntity().isEmpty();
                    Location location = item.getLocation();
                    boolean chunkLoaded = location.getWorld().isChunkLoaded(
                            location.getBlockX() >> 4, location.getBlockZ() >> 4);
                    if ((representationChanged || missingRequiredAnchor) && (packetOnly || chunkLoaded)) {
                        schedule(logical, true);
                    } else {
                        // If packet-only was disabled while the chunk is absent,
                        // hide its old fake viewers now; ChunkLoadEvent will create
                        // the real anchor without synchronously loading the chunk.
                        reconcileViewers(logical);
                    }
                } else if (logical instanceof DisplayEntity display && usesVirtualText(display)
                        && !virtualTextDisplays.containsKey(display)) {
                    schedule(logical, true);
                } else if (logical.getBukkitEntity().isEmpty() && requiresActualEntity(logical)) {
                    Location location = logical.getLocation();
                    if (location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                        schedule(logical, true);
                    }
                } else {
                    reconcileViewers(logical);
                }
            }
            if (!InteractionVisualizer.visibilityRateLimiting) {
                drainVisibilityShowQueues();
            }
        });
    }

    /** Retained API hook; viewer movement now drives exact path updates. */
    public static void dynamicEntity() {
    }

    public static void shutdown() {
        Runnable cleanup = () -> {
            InteractionVisualizer.viewerCullingManager.shutdown();
            InteractionVisualizer.viewerCullingManager = ViewerCullingManager.DISABLED;
            // An asynchronous removal can leave active before its main-thread
            // cleanup task runs. Include every representation index so disable
            // and hot reload still destroy client-only IDs instead of leaving
            // ghosts that no longer have bookkeeping.
            Set<VisualizerEntity> cleanupCandidates = shutdownCleanupCandidates(
                    active.keySet(), shownViewers.keySet(), virtualTextDisplays.keySet(),
                    virtualItemIds.keySet(), actualUuidByLogical.keySet());
            for (VisualizerEntity logical : cleanupCandidates) {
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
            forceScheduled.clear();
            logicalByActualUuid.clear();
            actualUuidByLogical.clear();
            logicalById.clear();
            logicalsByChunk.clear();
            chunkByLogical.clear();
            viewerChunks.clear();
            shownByViewer.clear();
            itemAnimations.clear();
            virtualItemIds.clear();
            renderedItemStacks.clear();
            renderedItemLocations.clear();
            packetItemPositions.clear();
            packetOnlyItems.clear();
            fixedItemDisplays.clear();
            virtualTextDisplays.clear();
            dynamicViewerPositions.clear();
            dirtyDynamicViewers.clear();
            clearAllVisibilityShowQueues();
            visibilityShowQueues.clear();
            pendingViewersByLogical.clear();
            pendingVisibilityViewers.clear();
            itemAnimationTickScheduled = false;
            dynamicViewerTickScheduled = false;
            dynamicTeleportFailureLogged = false;
            virtualTextAvailable = false;
            visibilityTickScheduled = false;
            playerStatus.clear();
            removeOwnedEntities(true);
        };
        if (Bukkit.isPrimaryThread()) {
            cleanup.run();
        } else {
            runSync(cleanup);
        }
    }

    @SafeVarargs
    static Set<VisualizerEntity> shutdownCleanupCandidates(
            Collection<? extends VisualizerEntity>... candidateGroups) {
        Set<VisualizerEntity> candidates = new HashSet<>();
        for (Collection<? extends VisualizerEntity> group : candidateGroups) {
            candidates.addAll(group);
        }
        return candidates;
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
        if (entity.isFixedDisplay()) {
            remove(null, entity, true);
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
            UUID viewer = player.getUniqueId();
            InteractionVisualizer.viewerCullingManager.clearViewer(viewer);
            clearVisibilityShowQueue(viewer);
            VirtualItemIdBuffer virtualItemIdsToRemove = new VirtualItemIdBuffer();
            Set<VisualizerEntity> shown = shownByViewer.remove(viewer);
            if (shown != null) {
                for (VisualizerEntity logical : shown) {
                    Set<UUID> viewers = shownViewers.get(logical);
                    if (viewers != null) {
                        viewers.remove(viewer);
                        if (viewers.isEmpty()) {
                            shownViewers.remove(logical, viewers);
                        }
                    }
                    if (logical instanceof DisplayEntity display
                            && virtualTextDisplays.containsKey(display)) {
                        removeVirtualText(display, viewer, player);
                    } else if (logical instanceof Item item) {
                        Integer id = forgetVirtualItem(item, viewer);
                        if (id != null) {
                            virtualItemIdsToRemove.add(id);
                        }
                    }
                    logical.getBukkitEntity().ifPresent(actual -> {
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
            UUID viewer = player.getUniqueId();
            if (viewerChunks.chunks(viewer).isEmpty()) {
                seedSentChunks(player);
            }
            forEachViewerLogical(viewer, logical -> {
                boolean virtualTextReady = logical instanceof DisplayEntity display
                        && usesVirtualText(display) && virtualTextDisplays.containsKey(display);
                if (logical.getBukkitEntity().isPresent() || packetOnlyItems.contains(logical)
                        || virtualTextReady) {
                    reconcileViewer(logical, player);
                } else if (logical instanceof DisplayEntity display && usesVirtualText(display)) {
                    schedule(logical, true);
                } else if (requiresActualEntity(logical)) {
                    schedule(logical, true);
                }
            });
        });
    }

    private static void schedule(VisualizerEntity logical, boolean force) {
        if (logical == null || plugin() == null || !plugin().isEnabled()) {
            return;
        }
        if (force) {
            // A force request may arrive while a normal sync is already queued.
            // Preserve the upgrade until whichever task observes it.
            forceScheduled.add(logical);
        }
        if (scheduled.add(logical)) {
            runSync(() -> {
                try {
                    boolean forceSync = forceScheduled.remove(logical);
                    if (active.containsKey(logical)) {
                        logicalById.put(logical.getUniqueId(), logical);
                        ChunkKey previousChunk = chunkByLogical.get(logical);
                        RepresentationKey previousRepresentation = representationKey(logical);
                        index(logical);
                        updateCullingBounds(logical);
                        int revision = logical.cacheCode();
                        if (forceSync || (requiresActualEntity(logical) && logical.getBukkitEntity().isEmpty())
                                || renderedRevision.getOrDefault(logical, Integer.MIN_VALUE) != revision) {
                            sync(logical, revision);
                        }
                        if (!Objects.equals(previousChunk, chunkByLogical.get(logical))
                                || !previousRepresentation.equals(representationKey(logical))) {
                            reconcileViewers(logical);
                        }
                    }
                } finally {
                    scheduled.remove(logical);
                }
                if (active.containsKey(logical) && (forceScheduled.contains(logical)
                        || renderedRevision.getOrDefault(logical, Integer.MIN_VALUE) != logical.cacheCode())) {
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
    }

    private static void syncDisplay(DisplayEntity logical) {
        PerformanceMetrics.displaySync();
        if (usesVirtualText(logical)) {
            syncVirtualTextDisplay(logical);
            return;
        }
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
            Location spawnLocation = text
                    ? DisplayTransformFactory.textDisplayLocation(logical)
                    : logical.getLocation();
            actual = text
                    ? logical.getWorld().spawn(spawnLocation, TextDisplay.class, DisplayManager::initializeDisplay)
                    : logical.getWorld().spawn(spawnLocation, org.bukkit.entity.ItemDisplay.class, DisplayManager::initializeDisplay);
            PerformanceMetrics.bukkitEntitySpawn();
            logical.bind(actual);
            trackActual(logical, actual);
        } else {
            actual = (Display) current;
        }

        applyBase(actual, logical);
        if (actual instanceof TextDisplay textDisplay) {
            applyTextDisplay(textDisplay, logical);
        } else {
            org.bukkit.entity.ItemDisplay itemDisplay = (org.bukkit.entity.ItemDisplay) actual;
            itemDisplay.setItemStack(logical.getDisplayItem());
            itemDisplay.setItemDisplayTransform(DisplayTransformFactory.itemDisplayTransform(logical));
            itemDisplay.setTransformationMatrix(DisplayTransformFactory.item(logical));
        }
    }

    private static void syncVirtualTextDisplay(DisplayEntity logical) {
        org.bukkit.entity.Entity current = logical.getBukkitEntity().orElse(null);
        if (current != null) {
            discardActual(logical, current);
        } else {
            untrackActual(logical);
            logical.unbind();
        }

        VirtualTextState state = virtualTextDisplays.get(logical);
        if (state == null) {
            state = new VirtualTextState(ClientTextDisplayBridge.create(), logical);
            VirtualTextState raced = virtualTextDisplays.putIfAbsent(logical, state);
            if (raced != null) {
                state = raced;
            }
        }

        Component text = logical.getCustomName() == null ? Component.empty() : logical.getCustomName();
        boolean metadataChanged = !Objects.equals(state.text, text);
        boolean positionChanged = state.positionProfileChanged(logical);
        state.capture(logical, text);
        index(logical);

        if (!shouldRender(logical)) {
            clearViewerTracking(logical);
            return;
        }

        Set<UUID> shown = shownViewers.get(logical);
        if (shown == null || shown.isEmpty() || (!metadataChanged && !positionChanged)) {
            return;
        }
        for (UUID viewer : new HashSet<>(shown)) {
            Player player = Bukkit.getPlayer(viewer);
            if (player == null || !player.isOnline()) {
                forgetShownViewer(logical, viewer, player);
                continue;
            }
            try {
                if (metadataChanged) {
                    state.display.updateMetaData(player, text);
                }
                if (positionChanged) {
                    synchronizeVirtualTextPosition(logical, player, true);
                }
            } catch (RuntimeException | LinkageError exception) {
                hideViewer(logical, viewer, player);
                logVirtualTextFailure("update", logical, exception);
            }
        }
    }

    static void applyTextDisplay(TextDisplay textDisplay, DisplayEntity logical) {
        textDisplay.text(logical.getCustomName() == null ? Component.empty() : logical.getCustomName());
        textDisplay.setLineWidth(logical.usesUnboundedTextWidth() ? Integer.MAX_VALUE : 200);
        textDisplay.setAlignment(TextDisplay.TextAlignment.CENTER);
        textDisplay.setDefaultBackground(logical.isDefaultBackground());
        textDisplay.setBackgroundColor(Color.fromARGB(0));
        textDisplay.setShadowed(false);
        // Preserve normal depth testing: floating text must be hidden by
        // opaque blocks between it and the viewer.
        textDisplay.setSeeThrough(false);
        textDisplay.setTransformationMatrix(DisplayTransformFactory.text(logical));
    }

    private static void initializeDisplay(Display display) {
        initializeEntity(display);
        display.setShadowRadius(0.0F);
        display.setShadowStrength(0.0F);
        display.setInterpolationDelay(0);
    }

    private static void applyBase(Display actual, DisplayEntity logical) {
        Location target = actual instanceof TextDisplay
                ? DisplayTransformFactory.textDisplayLocation(logical)
                : logical.getLocation();
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
        if (logical.isFixedDisplay()) {
            syncFixedItem(logical);
            return;
        }
        if (fixedItemDisplays.remove(logical)) {
            org.bukkit.entity.Entity previous = logical.getBukkitEntity().orElse(null);
            if (previous != null) {
                discardActual(logical, previous);
            } else {
                untrackActual(logical);
                logical.unbind();
                clearViewerTracking(logical);
            }
        }
        boolean packetOnly = qualifiesForPacketOnly(logical);
        boolean wasPacketOnly = packetOnlyItems.contains(logical);
        org.bukkit.entity.Entity current = logical.getBukkitEntity().orElse(null);
        Location logicalLocation = logical.getLocation();
        ItemAnimationState previousAnimation = itemAnimations.get(logical);

        if (packetOnly) {
            PerformanceMetrics.packetOnlyItemSync();
            ItemStack itemStack = logical.getItemStack();
            Location previousPosition = previousAnimation == null ? null : previousAnimation.position();
            Location previousLogicalLocation = previousAnimation == null
                    ? null : previousAnimation.logicalLocation();

            if (current != null) {
                discardActual(logical, current);
            } else if (!wasPacketOnly) {
                clearViewerTracking(logical);
                logical.unbind();
                untrackActual(logical);
            }

            packetOnlyItems.add(logical);
            // Visualizer Item getters already return defensive copies. Retain those
            // snapshots directly instead of cloning them a second time on every sync.
            ItemStack previousItemStack = renderedItemStacks.put(logical, itemStack);
            Location previousLocation = renderedItemLocations.put(logical, logicalLocation);
            boolean itemChanged = previousItemStack == null || !previousItemStack.equals(itemStack);
            boolean locationChanged = previousLocation == null || !previousLocation.equals(logicalLocation);
            Vector velocity = logical.getVelocity();
            boolean gravity = logical.hasGravity();
            boolean animated = requiresItemAnimation(gravity, velocity);
            ItemAnimationState nextAnimation = null;
            if (animated) {
                Location position = itemAnimationStartPosition(
                        logicalLocation, previousPosition, previousLogicalLocation, logicalLocation);
                nextAnimation = new ItemAnimationState(velocity, gravity, position, false, logicalLocation);
                itemAnimations.put(logical, nextAnimation);
                rememberPacketItemPosition(logical, nextAnimation.world,
                        nextAnimation.positionX, nextAnimation.positionY, nextAnimation.positionZ);
                scheduleItemAnimationTick();
                index(logical, nextAnimation.world, nextAnimation.positionX, nextAnimation.positionZ);
            } else {
                itemAnimations.remove(logical);
                rememberPacketItemPosition(logical, logicalLocation.getWorld(), logicalLocation.getX(),
                        logicalLocation.getY(), logicalLocation.getZ());
                index(logical, logicalLocation);
            }
            if (wasPacketOnly && itemChanged) {
                respawnVirtualItems(logical);
            } else if (wasPacketOnly && (locationChanged || previousAnimation != null || animated)) {
                Location visualLocation = nextAnimation == null ? logicalLocation : nextAnimation.position();
                synchronizeVirtualItemMotion(logical, visualLocation);
            }
            return;
        }

        if (wasPacketOnly) {
            packetOnlyItems.remove(logical);
            packetItemPositions.remove(logical);
            renderedItemLocations.remove(logical);
            itemAnimations.remove(logical);
            clearViewerTracking(logical);
            logical.unbind();
            untrackActual(logical);
        }

        if (current != null && (!current.getWorld().equals(logicalLocation.getWorld())
                || !(current instanceof ItemDisplay))) {
            discardActual(logical, current);
            current = null;
        }

        ItemDisplay actual;
        if (current instanceof ItemDisplay display) {
            actual = display;
        } else {
            clearViewerTracking(logical);
            actual = logicalLocation.getWorld().spawn(logicalLocation, ItemDisplay.class,
                    DisplayManager::initializeDisplay);
            PerformanceMetrics.bukkitEntitySpawn();
            logical.bind(actual);
            trackActual(logical, actual);
        }

        ItemStack itemStack = logical.getItemStack();
        ItemStack previousItemStack = renderedItemStacks.put(logical, itemStack);
        renderedItemLocations.remove(logical);
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

        Vector velocity = logical.getVelocity();
        boolean gravity = logical.hasGravity();
        boolean animated = requiresItemAnimation(gravity, velocity);
        Location previousPosition = previousAnimation == null ? null : previousAnimation.position();
        Location previousLogicalLocation = previousAnimation == null ? null : previousAnimation.logicalLocation();
        boolean applyLogicalLocation = shouldApplyItemLogicalLocation(
                animated, previousPosition, previousLogicalLocation, logicalLocation);
        boolean anchorMoved = applyItemBase(actual, logical, logicalLocation, applyLogicalLocation);

        ItemAnimationState nextAnimation = null;
        if (animated) {
            Location position = itemAnimationStartPosition(
                    actual.getLocation(), previousPosition, previousLogicalLocation, logicalLocation);
            nextAnimation = new ItemAnimationState(velocity, gravity, position,
                    useStaticAnchorForAnimation(InteractionVisualizer.staticVirtualItemAnchorsDuringAnimation,
                            logical.isCustomNameVisible()), logicalLocation);
            itemAnimations.put(logical, nextAnimation);
            scheduleItemAnimationTick();
        } else {
            itemAnimations.remove(logical);
        }

        Location anchorLocation = actual.getLocation();
        Location visualLocation = itemAnimationIndexLocation(
                nextAnimation != null && nextAnimation.staticAnchor,
                anchorLocation, nextAnimation == null ? null : nextAnimation.position());
        index(logical, visualLocation);

        if (itemChanged) {
            respawnVirtualItems(logical);
        } else if (requiresVirtualItemMotionSync(anchorMoved, previousAnimation != null, animated)) {
            synchronizeVirtualItemMotion(logical, visualLocation);
        }
    }

    private static void syncFixedItem(Item logical) {
        boolean representationChanged = fixedItemDisplays.add(logical);
        org.bukkit.entity.Entity current = logical.getBukkitEntity().orElse(null);
        Location logicalLocation = logical.getLocation();

        if (representationChanged) {
            packetOnlyItems.remove(logical);
            itemAnimations.remove(logical);
            renderedItemLocations.remove(logical);
            if (current != null) {
                discardActual(logical, current);
            } else {
                untrackActual(logical);
                logical.unbind();
                clearViewerTracking(logical);
            }
            current = null;
        } else if (current != null && (!current.getWorld().equals(logicalLocation.getWorld())
                || !(current instanceof ItemDisplay))) {
            discardActual(logical, current);
            current = null;
        }

        ItemStack itemStack = logical.getItemStack();
        renderedItemStacks.put(logical, itemStack);
        renderedItemLocations.remove(logical);
        if (itemStack.isEmpty()) {
            if (current != null) {
                discardActual(logical, current);
            } else {
                clearViewerTracking(logical);
            }
            index(logical, logicalLocation);
            return;
        }

        ItemDisplay actual;
        if (current instanceof ItemDisplay display) {
            actual = display;
        } else {
            clearViewerTracking(logical);
            actual = logicalLocation.getWorld().spawn(logicalLocation, ItemDisplay.class,
                    DisplayManager::initializeDisplay);
            PerformanceMetrics.bukkitEntitySpawn();
            logical.bind(actual);
            trackActual(logical, actual);
        }

        applyFixedItemBase(actual, logical, logicalLocation);
        actual.setItemStack(itemStack);
        actual.setItemDisplayTransform(DisplayTransformFactory.itemDisplayTransform(logical));
        actual.setTransformationMatrix(DisplayTransformFactory.item(logical));
        actual.setBillboard(Display.Billboard.FIXED);
        actual.setViewRange(1.0F);
        actual.setShadowRadius(0.0F);
        actual.setShadowStrength(0.0F);
        actual.setInterpolationDelay(0);
        actual.setInterpolationDuration(3);
        actual.setTeleportDuration(3);
        index(logical, logicalLocation);
    }

    private static void applyFixedItemBase(ItemDisplay actual, Item logical, Location target) {
        actual.setGlowing(logical.isGlowing());
        actual.customName(logical.getCustomName());
        actual.setCustomNameVisible(logical.isCustomNameVisible());
        Location current = actual.getLocation();
        boolean moved = !current.getWorld().equals(target.getWorld())
                || current.distanceSquared(target) > 1.0E-8
                || current.getYaw() != target.getYaw() || current.getPitch() != target.getPitch();
        if (moved) {
            PerformanceMetrics.bukkitEntityTeleport();
            actual.teleport(target);
        }
    }

    static boolean requiresVirtualItemMotionSync(boolean anchorMoved, boolean wasAnimated, boolean animated) {
        return anchorMoved || wasAnimated || animated;
    }

    private static boolean applyItemBase(org.bukkit.entity.Entity actual, Item logical,
                                         Location target, boolean applyLocation) {
        actual.setGlowing(logical.isGlowing());
        actual.customName(logical.getCustomName());
        actual.setCustomNameVisible(logical.isCustomNameVisible());
        boolean moved = applyLocation && (!actual.getWorld().equals(target.getWorld())
                || actual.getLocation().distanceSquared(target) > 1.0E-8);
        if (moved) {
            PerformanceMetrics.bukkitEntityTeleport();
            actual.teleport(target);
        }
        return moved;
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
            return !usesVirtualText(display) && shouldRender(display);
        }
        if (logical instanceof ItemFrame frame) {
            return shouldRender(frame);
        }
        if (logical instanceof Item item) {
            return item.isFixedDisplay() ? !item.getItemStack().isEmpty() : !qualifiesForPacketOnly(item);
        }
        return false;
    }

    private static boolean usesVirtualText(DisplayEntity logical) {
        return virtualTextAvailable && logical.usesLegacyNameTagStyle();
    }

    static boolean shouldRender(DisplayEntity logical) {
        return logical.isTextDisplay()
                ? logical.isCustomNameVisible() && !isEmpty(logical.getCustomName())
                : !logical.getDisplayItem().isEmpty();
    }

    private static boolean shouldRender(ItemFrame logical) {
        return !logical.getItem().isEmpty();
    }

    static boolean requiresItemAnimation(boolean gravity, Vector velocity) {
        return gravity || velocity.lengthSquared() > ITEM_ANIMATION_EPSILON;
    }

    static boolean itemAnimationLogicalLocationChanged(Location previousLogicalLocation,
                                                       Location logicalLocation) {
        if (previousLogicalLocation == null || logicalLocation == null) {
            return true;
        }
        return !Objects.equals(previousLogicalLocation.getWorld(), logicalLocation.getWorld())
                || previousLogicalLocation.getX() != logicalLocation.getX()
                || previousLogicalLocation.getY() != logicalLocation.getY()
                || previousLogicalLocation.getZ() != logicalLocation.getZ();
    }

    static boolean shouldApplyItemLogicalLocation(boolean animated,
                                                  Location previousAnimationPosition,
                                                  Location previousLogicalLocation,
                                                  Location logicalLocation) {
        return previousAnimationPosition == null || !animated
                || itemAnimationLogicalLocationChanged(previousLogicalLocation, logicalLocation);
    }

    static Location itemAnimationStartPosition(Location actualLocation,
                                               Location previousAnimationPosition,
                                               Location previousLogicalLocation,
                                               Location logicalLocation) {
        Objects.requireNonNull(actualLocation, "actualLocation");
        if (previousAnimationPosition == null
                || itemAnimationLogicalLocationChanged(previousLogicalLocation, logicalLocation)) {
            return actualLocation.clone();
        }
        return previousAnimationPosition.clone();
    }

    static Location itemAnimationIndexLocation(boolean staticAnchor, Location anchorLocation,
                                               Location animationPosition) {
        Objects.requireNonNull(anchorLocation, "anchorLocation");
        return (staticAnchor || animationPosition == null ? anchorLocation : animationPosition).clone();
    }

    static boolean useStaticAnchorForAnimation(boolean configured, boolean customNameVisible) {
        return configured && !customNameVisible;
    }

    static boolean qualifiesForPacketOnlyStatic(boolean configured, boolean gravity, Vector velocity,
                                                  boolean customNameVisible, boolean glowing) {
        return configured && !gravity && velocity != null
                && velocity.getX() == 0.0D && velocity.getY() == 0.0D && velocity.getZ() == 0.0D
                && !customNameVisible && !glowing;
    }

    static boolean qualifiesForPacketOnlyAnimated(boolean configured, boolean gravity, Vector velocity,
                                                   boolean customNameVisible, boolean glowing) {
        return configured && velocity != null && requiresItemAnimation(gravity, velocity)
                && !customNameVisible && !glowing;
    }

    private static boolean qualifiesForPacketOnly(Item logical) {
        if (logical.isFixedDisplay()) {
            return false;
        }
        Vector velocity = logical.getVelocity();
        boolean gravity = logical.hasGravity();
        return qualifiesForPacketOnlyStatic(InteractionVisualizer.packetOnlyStaticVirtualItems,
                gravity, velocity, logical.isCustomNameVisible(), logical.isGlowing())
                || qualifiesForPacketOnlyAnimated(InteractionVisualizer.packetOnlyAnimatedVirtualItems,
                gravity, velocity, logical.isCustomNameVisible(), logical.isGlowing());
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
        boolean packetOnly = packetOnlyItems.contains(logical);
        if (!active.containsKey(logical) || !packetOnly && !(entity instanceof ItemDisplay)) {
            itemAnimations.remove(logical, animation);
            return;
        }
        ItemDisplay actual = entity instanceof ItemDisplay display ? display : null;

        double movementX = animation.velocityX;
        double movementY = animation.velocityY - (animation.gravity ? ITEM_GRAVITY_PER_TICK : 0.0D);
        double movementZ = animation.velocityZ;
        double destinationX = animation.positionX + movementX;
        double destinationY = animation.positionY + movementY;
        double destinationZ = animation.positionZ + movementZ;
        if (!Double.isFinite(destinationX) || !Double.isFinite(destinationY)
                || !Double.isFinite(destinationZ)) {
            throw new IllegalArgumentException("Non-finite item animation position");
        }
        World world = animation.world;
        if (destinationY < world.getMinHeight() - ITEM_VOID_MARGIN
                || !world.isChunkLoaded(blockCoordinate(destinationX) >> 4,
                blockCoordinate(destinationZ) >> 4)) {
            remove(null, logical, true);
            return;
        }

        animation.positionX = destinationX;
        animation.positionY = destinationY;
        animation.positionZ = destinationZ;
        animation.velocityX = movementX * ITEM_HORIZONTAL_DRAG_PER_TICK;
        animation.velocityY = movementY * ITEM_VERTICAL_DRAG_PER_TICK;
        animation.velocityZ = movementZ * ITEM_HORIZONTAL_DRAG_PER_TICK;
        if (packetOnlyItems.contains(logical)) {
            rememberPacketItemPosition(logical, world, destinationX, destinationY, destinationZ);
        }
        if (!packetOnly && !animation.staticAnchor) {
            Location destination = animation.position();
            PerformanceMetrics.bukkitEntityTeleport();
            if (!actual.teleport(destination)) {
                remove(null, logical, true);
                return;
            }
        }
        boolean chunkChanged = !animation.staticAnchor
                && index(logical, world, destinationX, destinationZ);
        updateCullingBounds(logical);
        if (chunkChanged) {
            reconcileViewers(logical);
        }
        if (animation.gravity) {
            // Heart's fake item is no-gravity. Absolute correction preserves the
            // vanilla gravity trajectory; no-gravity motion remains client-run.
            synchronizeVirtualItemMotion(logical, world, destinationX, destinationY, destinationZ);
        }
        if (!animation.gravity && animation.velocityLengthSquared() <= ITEM_ANIMATION_EPSILON) {
            itemAnimations.remove(logical, animation);
            if (!packetOnly && animation.staticAnchor) {
                Location destination = animation.position();
                PerformanceMetrics.bukkitEntityTeleport();
                if (!actual.teleport(destination)) {
                    remove(null, logical, true);
                    return;
                }
                if (index(logical, actual.getLocation())) {
                    reconcileViewers(logical);
                }
            }
            synchronizeVirtualItemMotion(logical, world, destinationX, destinationY, destinationZ);
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

    private static boolean spawnVirtualText(DisplayEntity logical, Player player) {
        VirtualTextState state = virtualTextDisplays.get(logical);
        Set<UUID> shown = shownViewers.get(logical);
        Location anchor = logical.getLocation();
        UUID viewer = player.getUniqueId();
        if (!usesVirtualText(logical) || state == null || !active.containsKey(logical)
                || shown == null || !shown.contains(viewer) || !shouldRender(logical)
                || !player.isOnline() || !player.getWorld().equals(anchor.getWorld())
                || !player.isChunkSent(Chunk.getChunkKey(anchor))) {
            return false;
        }

        Location target = virtualTextLocation(logical, player);
        try {
            state.display.spawn(player, target, state.text);
            PerformanceMetrics.virtualSpawnBundle();
            if (logical instanceof BillboardDisplayEntity billboard) {
                rememberDynamicViewer(billboard, viewer, target);
            }
            return true;
        } catch (RuntimeException | LinkageError exception) {
            forgetDynamicViewer(logical, viewer);
            logVirtualTextFailure("spawn", logical, exception);
            return false;
        }
    }

    private static Location virtualTextLocation(DisplayEntity logical, Player player) {
        Location logicalLocation = logical.getLocation();
        if (logical instanceof BillboardDisplayEntity billboard) {
            Location eye = player.getEyeLocation();
            logicalLocation = billboard.getViewingLocation(eye, eye.getDirection());
        }
        return DisplayTransformFactory.textDisplayLocation(logical, logicalLocation);
    }

    private static void removeVirtualText(DisplayEntity logical, UUID viewer, Player player) {
        forgetDynamicViewer(logical, viewer);
        VirtualTextState state = virtualTextDisplays.get(logical);
        if (state == null || player == null || !player.isOnline()) {
            return;
        }
        try {
            state.display.destroy(player);
            PerformanceMetrics.virtualRemovePacket();
        } catch (RuntimeException | LinkageError exception) {
            logVirtualTextFailure("remove", logical, exception);
        }
    }

    private static void logVirtualTextFailure(String operation, DisplayEntity logical,
                                              Throwable exception) {
        Plugin plugin = plugin();
        if (plugin != null) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to " + operation + " a packet-only text display at "
                            + logical.getLocation(), exception);
        }
    }

    private static boolean spawnVirtualItem(Item logical, Player player) {
        // Follow the backend that is currently installed, not a render-mode
        // mutation that may still be waiting for its main-thread sync.
        if (fixedItemDisplays.contains(logical)) {
            return false;
        }
        org.bukkit.entity.Entity anchor = logical.getBukkitEntity().orElse(null);
        Set<UUID> shown = shownViewers.get(logical);
        boolean packetOnly = packetOnlyItems.contains(logical);
        ItemAnimationState animation = itemAnimations.get(logical);
        Location source;
        if (!active.containsKey(logical) || shown == null || !shown.contains(player.getUniqueId())
                || !player.isOnline()) {
            return false;
        }
        if (packetOnly) {
            PacketItemPosition packetPosition = packetItemPositions.get(logical);
            source = packetPosition == null ? logical.getLocation() : packetPosition.location();
            if (!player.getWorld().equals(source.getWorld())
                    || !player.isChunkSent(Chunk.getChunkKey(source))) {
                return false;
            }
        } else {
            if (!(anchor instanceof ItemDisplay) || !player.getWorld().equals(anchor.getWorld())) {
                return false;
            }
            source = animation == null ? anchor.getLocation() : animation.position();
        }

        Map<UUID, Integer> ids = virtualItemIds.computeIfAbsent(logical, ignored -> new ConcurrentHashMap<>());
        UUID viewer = player.getUniqueId();
        if (ids.containsKey(viewer)) {
            return true;
        }

        Integer spawnedId = null;
        try {
            spawnedId = SparrowHeart.getInstance().dropFakeItem(
                    player, logical.getItemStack(), source);
            PerformanceMetrics.virtualSpawnBundle();
            ids.put(viewer, spawnedId);
            if (animation != null) {
                Vector motion = animation.nextMovement();
                if (motion.lengthSquared() > ITEM_ANIMATION_EPSILON) {
                    SparrowHeart.getInstance().sendClientSideEntityMotion(player, motion, spawnedId);
                    PerformanceMetrics.virtualMotionBundle();
                }
            }
            return true;
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
            return false;
        }
    }

    private static Vector nextVirtualItemMotion(Item logical) {
        ItemAnimationState animation = itemAnimations.get(logical);
        return animation == null
                ? new Vector()
                : animation.nextMovement();
    }

    private static void rememberPacketItemPosition(Item logical, World world,
                                                   double x, double y, double z) {
        PacketItemPosition position = packetItemPositions.get(logical);
        if (position == null) {
            packetItemPositions.put(logical, new PacketItemPosition(world, x, y, z));
        } else {
            position.world = world;
            position.x = x;
            position.y = y;
            position.z = z;
        }
    }

    private static void synchronizeVirtualItemMotion(Item logical, Location location) {
        synchronizeVirtualItemMotion(logical, location.getWorld(),
                location.getX(), location.getY(), location.getZ());
    }

    private static void synchronizeVirtualItemMotion(Item logical, World world,
                                                     double x, double y, double z) {
        Map<UUID, Integer> ids = virtualItemIds.get(logical);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Location location = new Location(world, x, y, z);
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
        boolean packetOnly = packetOnlyItems.contains(logical);
        for (UUID viewer : viewers) {
            Player player = Bukkit.getPlayer(viewer);
            removeVirtualItem(logical, viewer, player);
            boolean spawned = player != null && spawnVirtualItem(logical, player);
            if (packetOnly && !spawned) {
                forgetShownViewer(logical, viewer, player);
            }
        }
    }

    private static void collectVirtualItem(Item logical, Player collector) {
        try {
            int amount = Math.max(1, logical.getItemStack().getAmount());
            boolean pickupPacketAvailable = ClientPickupAnimationBridge.initialize();
            if (pickupPacketAvailable) {
                materializeRateLimitedPickupViewers(logical, collector);
            }
            Map<UUID, Integer> ids = virtualItemIds.get(logical);
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

    /**
     * A native pickup lasts only three client ticks, so it cannot wait behind the
     * visibility token bucket. Materialize only eligible viewers that do not yet
     * own the fake item, then let the normal pickup path claim and remove it in
     * the same server tick. This preserves rate limiting for persistent displays
     * without reintroducing a server-side pickup trajectory or delayed task.
     */
    private static void materializeRateLimitedPickupViewers(Item logical, Player collector) {
        if (!InteractionVisualizer.visibilityRateLimiting) {
            return;
        }
        Collection<Player> desiredPlayers = active.get(logical);
        if (desiredPlayers == null || !collector.isOnline()) {
            return;
        }

        boolean packetOnly = packetOnlyItems.contains(logical);
        Location packetOnlyLocation = packetOnly ? logical.getLocation() : null;
        for (Player player : desiredPlayers) {
            if (player == null) {
                continue;
            }
            UUID viewer = player.getUniqueId();
            Map<UUID, Integer> ids = virtualItemIds.get(logical);
            boolean hasVirtualItem = ids != null && ids.containsKey(viewer);
            boolean collectorReachable = player.isOnline() && player.getWorld().equals(collector.getWorld());
            boolean renderable = collectorReachable
                    && isViewerRenderable(logical, player, packetOnlyLocation, packetOnly);
            if (!shouldMaterializeRateLimitedPickupViewer(
                    true, renderable, collectorReachable, hasVirtualItem)) {
                continue;
            }

            cancelVisibilityShow(logical, viewer);
            Set<UUID> shown = shownViewers.get(logical);
            if (shown == null || !shown.contains(viewer)) {
                showViewerNow(logical, player);
            }
            // Entity tracking can be deferred even after showEntity(). Claim the
            // virtual item synchronously so the following take packet always has
            // a source entity that the client already knows about.
            spawnVirtualItem(logical, player);
        }
    }

    static boolean shouldMaterializeRateLimitedPickupViewer(boolean rateLimiting,
                                                             boolean renderable,
                                                             boolean collectorReachable,
                                                             boolean hasVirtualItem) {
        return rateLimiting && renderable && collectorReachable && !hasVirtualItem;
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

    private static void removeVirtualItems(Player player, VirtualItemIdBuffer ids) {
        if (ids.isEmpty() || player == null || !player.isOnline()) {
            return;
        }
        for (int start = 0; start < ids.size(); start += VIRTUAL_REMOVE_BATCH_SIZE) {
            int end = Math.min(ids.size(), start + VIRTUAL_REMOVE_BATCH_SIZE);
            int[] batch = new int[end - start];
            ids.copyInto(start, batch);
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

    private static RepresentationKey representationKey(VisualizerEntity logical) {
        boolean packetOnly = logical instanceof Item item && packetOnlyItems.contains(item);
        boolean virtualTextReady = logical instanceof DisplayEntity display && usesVirtualText(display)
                && virtualTextDisplays.containsKey(display) && shouldRender(display);
        return new RepresentationKey(actualUuidByLogical.get(logical), packetOnly, virtualTextReady);
    }

    private static void seedSentChunks(Player player) {
        UUID viewer = player.getUniqueId();
        viewerChunks.removeViewer(viewer);
        if (!player.isOnline()) {
            return;
        }
        Location location = player.getLocation();
        UUID world = player.getWorld().getUID();
        int centerX = location.getBlockX() >> 4;
        int centerZ = location.getBlockZ() >> 4;
        int radius = Math.max(2, Bukkit.getServer().getViewDistance() + 1);
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                if (player.isChunkSent(Chunk.getChunkKey(x, z))) {
                    viewerChunks.add(viewer, new ChunkKey(world, x, z));
                }
            }
        }
    }

    private static void forEachViewerLogical(UUID viewer, Consumer<VisualizerEntity> action) {
        for (ChunkKey key : viewerChunks.chunks(viewer)) {
            Set<VisualizerEntity> logicals = logicalsByChunk.get(key);
            if (logicals != null) {
                for (VisualizerEntity logical : logicals) {
                    action.accept(logical);
                }
            }
        }
    }

    private static void index(VisualizerEntity logical) {
        if (logical instanceof Item item) {
            ItemAnimationState animation = itemAnimations.get(item);
            if (animation != null) {
                if (animation.staticAnchor) {
                    Location anchorLocation = logical.getBukkitEntity()
                            .map(org.bukkit.entity.Entity::getLocation)
                            .orElseGet(logical::getLocation);
                    index(logical, anchorLocation);
                } else {
                    index(logical, animation.world, animation.positionX, animation.positionZ);
                }
                return;
            }
        }
        index(logical, logical.getLocation());
    }

    static boolean index(VisualizerEntity logical, Location location) {
        return index(logical, location.getWorld(), location.getX(), location.getZ());
    }

    private static boolean index(VisualizerEntity logical, World world, double x, double z) {
        UUID worldId = world.getUID();
        int chunkX = blockCoordinate(x) >> 4;
        int chunkZ = blockCoordinate(z) >> 4;
        ChunkKey previous = chunkByLogical.get(logical);
        if (previous != null && previous.world().equals(worldId)
                && previous.x() == chunkX && previous.z() == chunkZ) {
            return false;
        }
        ChunkKey current = new ChunkKey(
                worldId, chunkX, chunkZ);
        previous = chunkByLogical.put(logical, current);
        boolean migrated = previous != null && !previous.equals(current);
        if (migrated) {
            Set<VisualizerEntity> previousEntries = logicalsByChunk.get(previous);
            if (previousEntries != null) {
                previousEntries.remove(logical);
                if (previousEntries.isEmpty()) {
                    logicalsByChunk.remove(previous, previousEntries);
                }
            }
        }
        logicalsByChunk.computeIfAbsent(current, ignored -> ConcurrentHashMap.newKeySet()).add(logical);
        return migrated;
    }

    private static int blockCoordinate(double coordinate) {
        return (int) Math.floor(coordinate);
    }

    static void unindex(VisualizerEntity logical) {
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
        InteractionVisualizer.viewerCullingManager.clearLogical(logical.getUniqueId());
        cancelVisibilityShows(logical);
        if (logical instanceof BillboardDisplayEntity billboard) {
            forgetDynamicEntity(billboard);
        }
        if (logical instanceof Item item) {
            destroyVirtualItems(item);
        }
        Set<UUID> shown = shownViewers.remove(logical);
        if (shown == null) {
            return;
        }
        for (UUID uuid : shown) {
            Player player = Bukkit.getPlayer(uuid);
            Set<VisualizerEntity> viewerShown = shownByViewer.get(uuid);
            if (viewerShown != null) {
                viewerShown.remove(logical);
                if (viewerShown.isEmpty()) {
                    shownByViewer.remove(uuid, viewerShown);
                }
            }
            if (logical instanceof DisplayEntity display && virtualTextDisplays.containsKey(display)) {
                removeVirtualText(display, uuid, player);
            }
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

    private static boolean isViewerEnabledFor(VisualizerEntity logical, Player player) {
        Collection<Player> desiredPlayers = active.get(logical);
        if (desiredPlayers == null || player == null) {
            return false;
        }
        UUID viewer = player.getUniqueId();
        return SynchronizedFilteredCollection.anyMatch(desiredPlayers,
                candidate -> candidate != null && viewer.equals(candidate.getUniqueId()));
    }

    private static boolean isViewerDesired(VisualizerEntity logical, Player player) {
        return isViewerRenderable(logical, player) && isViewerEnabledFor(logical, player);
    }

    private static boolean usesCraftEngineCulling(VisualizerEntity logical) {
        // Per-viewer billboard paths move around their anchor and therefore do
        // not have one shared AABB. Keep their exact existing visibility path.
        return InteractionVisualizer.viewerCullingManager.enabled()
                && !(logical instanceof BillboardDisplayEntity);
    }

    private static void updateCullingBounds(VisualizerEntity logical) {
        if (!usesCraftEngineCulling(logical) || !active.containsKey(logical)) {
            return;
        }
        InteractionVisualizer.viewerCullingManager.update(
                logical.getUniqueId(), cullingBounds(logical));
    }

    private static CullingBounds cullingBounds(VisualizerEntity logical) {
        double x;
        double y;
        double z;
        if (logical instanceof Item item) {
            ItemAnimationState animation = itemAnimations.get(item);
            if (animation != null) {
                x = animation.positionX;
                y = animation.positionY;
                z = animation.positionZ;
            } else {
                PacketItemPosition packetPosition = packetItemPositions.get(item);
                if (packetPosition != null) {
                    x = packetPosition.x;
                    y = packetPosition.y;
                    z = packetPosition.z;
                } else {
                    Location location = logical.getLocation();
                    x = location.getX();
                    y = location.getY();
                    z = location.getZ();
                }
            }
        } else {
            Location location = logical.getLocation();
            x = location.getX();
            y = location.getY();
            z = location.getZ();
        }
        double width = logical instanceof ItemFrame ? 0.75D
                : logical instanceof Item ? 0.5D : 1.0D;
        double height = Math.max(0.25D, logical.getHeight());
        double halfWidth = width * 0.5D;
        return new CullingBounds(x - halfWidth, y, z - halfWidth,
                x + halfWidth, y + height, z + halfWidth,
                0, 0.2D, true);
    }

    private static boolean isQueuedViewerStillRenderable(VisualizerEntity logical, Player player) {
        // Eligibility was checked before enqueueing. Every internal eligibility
        // mutation (preference reset, reload, world/chunk lifecycle, removal)
        // explicitly cancels or reconciles the queue, so the hot drain path only
        // needs O(1) liveness/renderability validation.
        return active.containsKey(logical) && isViewerRenderable(logical, player);
    }

    private static boolean isViewerRenderable(VisualizerEntity logical, Player player) {
        if (logical instanceof DisplayEntity display && usesVirtualText(display)) {
            return isViewerRenderable(logical, player, display.getLocation(),
                    virtualTextDisplays.containsKey(display) && shouldRender(display));
        }
        if (logical instanceof Item item && packetOnlyItems.contains(item)) {
            return isViewerRenderable(logical, player, item.getLocation(), true);
        }
        return isViewerRenderable(logical, player, null, false);
    }

    private static boolean isViewerRenderable(VisualizerEntity logical, Player player,
                                               Location packetOnlyLocation, boolean packetOnlyQualified) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        if (logical instanceof DisplayEntity display && usesVirtualText(display)) {
            if (packetOnlyLocation == null || !player.getWorld().equals(packetOnlyLocation.getWorld())) {
                return false;
            }
            PerformanceMetrics.virtualViewerChecks(1);
            ChunkKey key = chunkByLogical.get(logical);
            return packetOnlyQualified && key != null
                    && viewerChunks.contains(player.getUniqueId(), key);
        }
        if (logical instanceof Item && packetOnlyItems.contains((Item) logical)) {
            if (packetOnlyLocation == null || !player.getWorld().equals(packetOnlyLocation.getWorld())) {
                return false;
            }
            PerformanceMetrics.virtualViewerChecks(1);
            ChunkKey key = chunkByLogical.get(logical);
            return packetOnlyQualified && key != null
                    && viewerChunks.contains(player.getUniqueId(), key);
        }
        org.bukkit.entity.Entity actual = logical.getBukkitEntity().orElse(null);
        if (actual == null || !player.getWorld().equals(actual.getWorld())) {
            return false;
        }

        // showEntity() records Paper's visibility override even when the
        // entity tracker cannot send a spawn yet. In particular, ChunkLoadEvent
        // can recreate a non-persistent display before the destination chunk
        // packet reaches the player. Waiting for the sent-chunk lifecycle keeps
        // shownViewers from turning that lost first attempt into a permanent
        // omission. The chunk index avoids allocating a Location in this hot
        // eligibility check.
        ChunkKey key = chunkByLogical.get(logical);
        if (key == null || !key.world().equals(player.getWorld().getUID())) {
            return false;
        }
        return viewerChunks.contains(player.getUniqueId(), key);
    }

    private static void requestViewerShow(VisualizerEntity logical, Player player) {
        Set<UUID> shown = shownViewers.get(logical);
        if (shown != null && shown.contains(player.getUniqueId())) {
            cancelVisibilityShow(logical, player.getUniqueId());
            return;
        }
        if (!InteractionVisualizer.visibilityRateLimiting) {
            cancelVisibilityShow(logical, player.getUniqueId());
            showViewerNow(logical, player);
            return;
        }

        VisibilityShowQueue<VisualizerEntity> queue = visibilityShowQueues.computeIfAbsent(
                player.getUniqueId(), ignored -> new VisibilityShowQueue<>(
                        InteractionVisualizer.visibilityRateLimitBucketSize, currentServerTick()));
        if (queue.request(logical)) {
            UUID viewer = player.getUniqueId();
            pendingViewersByLogical.computeIfAbsent(logical,
                    ignored -> ConcurrentHashMap.newKeySet()).add(viewer);
            pendingVisibilityViewers.add(viewer);
            PerformanceMetrics.visibilityShowQueued();
        }
        scheduleVisibilityTick();
    }

    private static void showViewerNow(VisualizerEntity logical, Player player) {
        // Both callers remove the pending request before entering: the immediate
        // path cancels it, while VisibilityShowQueue removes it before invoking
        // the drain action. Avoid a second queue lookup on every successful show.
        Set<UUID> shown = shownViewers.computeIfAbsent(logical, ignored -> ConcurrentHashMap.newKeySet());
        UUID viewer = player.getUniqueId();
        forgetPending(logical, viewer);
        if (!shown.add(viewer)) {
            return;
        }

        boolean shownSuccessfully;
        if (logical instanceof DisplayEntity display && usesVirtualText(display)) {
            shownSuccessfully = spawnVirtualText(display, player);
        } else if (logical instanceof Item item && packetOnlyItems.contains(item)) {
            shownSuccessfully = spawnVirtualItem(item, player);
        } else {
            org.bukkit.entity.Entity actual = logical.getBukkitEntity().orElse(null);
            shownSuccessfully = actual != null;
            if (shownSuccessfully) {
                PerformanceMetrics.bukkitShow();
                player.showEntity(plugin(), actual);
            }
        }

        if (shownSuccessfully) {
            shownByViewer.computeIfAbsent(viewer, ignored -> ConcurrentHashMap.newKeySet()).add(logical);
            playerStatus.computeIfAbsent(player, ignored -> ConcurrentHashMap.newKeySet()).add(logical);
        } else {
            forgetShownViewer(logical, viewer, player);
        }
    }

    private static void hideViewer(VisualizerEntity logical, UUID viewer, Player player) {
        cancelVisibilityShow(logical, viewer);
        Set<UUID> shown = shownViewers.get(logical);
        boolean wasShown = shown != null && shown.remove(viewer);
        if (shown != null && shown.isEmpty()) {
            shownViewers.remove(logical, shown);
        }
        Set<VisualizerEntity> viewerShown = shownByViewer.get(viewer);
        if (viewerShown != null) {
            viewerShown.remove(logical);
            if (viewerShown.isEmpty()) {
                shownByViewer.remove(viewer, viewerShown);
            }
        }
        if (wasShown && logical instanceof DisplayEntity display && virtualTextDisplays.containsKey(display)) {
            removeVirtualText(display, viewer, player);
        } else {
            forgetDynamicViewer(logical, viewer);
        }
        if (logical instanceof Item item) {
            removeVirtualItem(item, viewer, player);
        }
        org.bukkit.entity.Entity actual = logical.getBukkitEntity().orElse(null);
        if (wasShown && actual != null && player != null) {
            PerformanceMetrics.bukkitHide();
            player.hideEntity(plugin(), actual);
        }
        if (player != null) {
            Set<VisualizerEntity> status = playerStatus.get(player);
            if (status != null) {
                status.remove(logical);
            }
        }
    }

    private static Integer forgetShownViewer(VisualizerEntity logical, UUID viewer, Player player) {
        cancelVisibilityShow(logical, viewer);
        forgetDynamicViewer(logical, viewer);
        Set<UUID> shown = shownViewers.get(logical);
        if (shown != null) {
            shown.remove(viewer);
            if (shown.isEmpty()) {
                shownViewers.remove(logical, shown);
            }
        }
        Set<VisualizerEntity> viewerShown = shownByViewer.get(viewer);
        if (viewerShown != null) {
            viewerShown.remove(logical);
            if (viewerShown.isEmpty()) {
                shownByViewer.remove(viewer, viewerShown);
            }
        }
        Integer forgottenVirtualId = logical instanceof Item item ? forgetVirtualItem(item, viewer) : null;
        if (player != null) {
            Set<VisualizerEntity> status = playerStatus.get(player);
            if (status != null) {
                status.remove(logical);
            }
        }
        return forgottenVirtualId;
    }

    private static void cancelVisibilityShow(VisualizerEntity logical, UUID viewer) {
        VisibilityShowQueue<VisualizerEntity> queue = visibilityShowQueues.get(viewer);
        if (queue != null && queue.cancel(logical)) {
            forgetPending(logical, viewer);
            if (!queue.hasPending()) {
                pendingVisibilityViewers.remove(viewer);
            }
        }
    }

    private static void cancelVisibilityShows(VisualizerEntity logical) {
        Set<UUID> viewers = pendingViewersByLogical.remove(logical);
        if (viewers == null) {
            return;
        }
        for (UUID viewer : viewers) {
            VisibilityShowQueue<VisualizerEntity> queue = visibilityShowQueues.get(viewer);
            if (queue != null) {
                queue.cancel(logical);
                if (!queue.hasPending()) {
                    pendingVisibilityViewers.remove(viewer);
                }
            }
        }
    }

    private static void clearVisibilityShowQueue(UUID viewer) {
        VisibilityShowQueue<VisualizerEntity> queue = visibilityShowQueues.remove(viewer);
        if (queue != null) {
            queue.clear(logical -> forgetPending(logical, viewer));
        }
        pendingVisibilityViewers.remove(viewer);
    }

    private static void clearAllVisibilityShowQueues() {
        for (UUID viewer : visibilityShowQueues.keySet()) {
            clearVisibilityShowQueue(viewer);
        }
    }

    private static void forgetPending(VisualizerEntity logical, UUID viewer) {
        Set<UUID> viewers = pendingViewersByLogical.get(logical);
        if (viewers != null) {
            viewers.remove(viewer);
            if (viewers.isEmpty()) {
                pendingViewersByLogical.remove(logical, viewers);
            }
        }
    }

    private static void scheduleVisibilityTick() {
        if (visibilityTickScheduled || !hasPendingVisibilityShows()
                || plugin() == null || !plugin().isEnabled()) {
            return;
        }
        visibilityTickScheduled = true;
        Bukkit.getScheduler().runTaskLater(plugin(), () -> {
            try {
                drainVisibilityShowQueues();
            } finally {
                visibilityTickScheduled = false;
                scheduleVisibilityTick();
            }
        }, 1L);
    }

    private static void drainVisibilityShowQueues() {
        boolean limited = InteractionVisualizer.visibilityRateLimiting;
        int capacity = InteractionVisualizer.visibilityRateLimitBucketSize;
        int refill = InteractionVisualizer.visibilityRateLimitRestorePerTick;
        long currentTick = limited ? currentServerTick() : 0L;
        for (UUID viewer : pendingVisibilityViewers) {
            VisibilityShowQueue<VisualizerEntity> queue = visibilityShowQueues.get(viewer);
            if (queue == null) {
                pendingVisibilityViewers.remove(viewer);
                continue;
            }
            // Queue states deliberately survive while empty to retain token-bucket
            // credit. Skip them before player lookup/lambda/list allocation.
            if (!queue.hasPending()) {
                continue;
            }
            Player player = Bukkit.getPlayer(viewer);
            if (player == null || !player.isOnline()) {
                clearVisibilityShowQueue(viewer);
                continue;
            }

            VisibilityShowQueue.DrainAction<VisualizerEntity> showIfRenderable = logical -> {
                forgetPending(logical, viewer);
                if (!isQueuedViewerStillRenderable(logical, player)) {
                    return false;
                }
                PerformanceMetrics.visibilityShowDrained();
                showViewerNow(logical, player);
                return true;
            };
            if (limited) {
                queue.drainTo(capacity, refill, currentTick, showIfRenderable);
            } else {
                queue.drainAllTo(showIfRenderable);
            }
            if (!queue.hasPending()) {
                pendingVisibilityViewers.remove(viewer);
            }
        }
    }

    private static boolean hasPendingVisibilityShows() {
        return !pendingVisibilityViewers.isEmpty();
    }

    private static long currentServerTick() {
        return Integer.toUnsignedLong(Bukkit.getCurrentTick());
    }

    private static void markDynamicViewerDirty(Player player) {
        UUID viewer = player.getUniqueId();
        Map<BillboardDisplayEntity, DynamicViewerPosition> positions = dynamicViewerPositions.get(viewer);
        if (positions == null || positions.isEmpty()) {
            return;
        }
        dirtyDynamicViewers.add(viewer);
        scheduleDynamicViewerTick();
    }

    private static void scheduleDynamicViewerTick() {
        if (dynamicViewerTickScheduled || dirtyDynamicViewers.isEmpty()) {
            return;
        }
        dynamicViewerTickScheduled = true;
        Bukkit.getScheduler().runTaskLater(plugin(), () -> {
            dynamicViewerTickScheduled = false;
            Set<UUID> viewers = new HashSet<>(dirtyDynamicViewers);
            dirtyDynamicViewers.removeAll(viewers);
            for (UUID viewer : viewers) {
                Player player = Bukkit.getPlayer(viewer);
                if (player != null && player.isOnline()) {
                    synchronizeDynamicViewer(player);
                }
            }
            scheduleDynamicViewerTick();
        }, 2L);
    }

    private static void synchronizeDynamicViewer(Player player) {
        Map<BillboardDisplayEntity, DynamicViewerPosition> positions =
                dynamicViewerPositions.get(player.getUniqueId());
        if (positions == null || positions.isEmpty()) {
            return;
        }
        for (BillboardDisplayEntity billboard : positions.keySet()) {
            synchronizeDynamicViewer(billboard, player, false);
        }
    }

    private static void synchronizeDynamicViewer(BillboardDisplayEntity logical, Player player,
                                                  boolean force) {
        synchronizeVirtualTextPosition(logical, player, force);
    }

    private static boolean synchronizeVirtualTextPosition(DisplayEntity logical, Player player,
                                                           boolean force) {
        UUID viewer = player.getUniqueId();
        Set<UUID> shown = shownViewers.get(logical);
        VirtualTextState state = virtualTextDisplays.get(logical);
        Location anchor = logical.getLocation();
        if (shown == null || !shown.contains(viewer) || state == null
                || !player.isOnline() || !player.getWorld().equals(anchor.getWorld())) {
            forgetDynamicViewer(logical, viewer);
            return false;
        }

        Location target = virtualTextLocation(logical, player);
        if (!force && logical instanceof BillboardDisplayEntity billboard) {
            Map<BillboardDisplayEntity, DynamicViewerPosition> positions = dynamicViewerPositions.get(viewer);
            DynamicViewerPosition previous = positions == null ? null : positions.get(billboard);
            if (previous != null && previous.matches(target)) {
                return true;
            }
        }

        try {
            state.display.teleport(player, target);
            PerformanceMetrics.virtualTeleportBundle();
            if (logical instanceof BillboardDisplayEntity billboard) {
                rememberDynamicViewer(billboard, viewer, target);
            }
            return true;
        } catch (RuntimeException | LinkageError exception) {
            forgetDynamicViewer(logical, viewer);
            if (!dynamicTeleportFailureLogged) {
                dynamicTeleportFailureLogged = true;
                plugin().getLogger().log(Level.WARNING,
                        "Unable to synchronize a per-viewer hologram path; further failures are suppressed",
                        exception);
            }
            // A failed teleport must not leave a static label permanently at
            // its old anchor. Recreate the same client-only ID at the freshly
            // computed position; the normal visibility gate controls retries.
            hideViewer(logical, viewer, player);
            if (isViewerDesired(logical, player)) {
                requestViewerShow(logical, player);
            }
            return false;
        }
    }

    private static void rememberDynamicViewer(BillboardDisplayEntity logical, UUID viewer,
                                              Location target) {
        dynamicViewerPositions.computeIfAbsent(viewer, ignored -> new ConcurrentHashMap<>())
                .put(logical, DynamicViewerPosition.at(target));
    }

    private static void forgetDynamicViewer(VisualizerEntity logical, UUID viewer) {
        if (!(logical instanceof BillboardDisplayEntity billboard)) {
            return;
        }
        Map<BillboardDisplayEntity, DynamicViewerPosition> positions = dynamicViewerPositions.get(viewer);
        if (positions != null) {
            positions.remove(billboard);
            if (positions.isEmpty()) {
                dynamicViewerPositions.remove(viewer, positions);
            }
        }
    }

    private static void forgetDynamicViewer(UUID viewer) {
        dirtyDynamicViewers.remove(viewer);
        dynamicViewerPositions.remove(viewer);
    }

    private static void forgetDynamicEntity(BillboardDisplayEntity logical) {
        for (Map.Entry<UUID, Map<BillboardDisplayEntity, DynamicViewerPosition>> entry
                : dynamicViewerPositions.entrySet()) {
            Map<BillboardDisplayEntity, DynamicViewerPosition> positions = entry.getValue();
            positions.remove(logical);
            if (positions.isEmpty()) {
                dynamicViewerPositions.remove(entry.getKey(), positions);
            }
        }
    }

    static boolean dynamicInputChanged(Location from, Location to) {
        return to != null && (from.getX() != to.getX() || from.getZ() != to.getZ()
                || from.getYaw() != to.getYaw());
    }

    private static void forgetPacketOnlyViewer(Player player) {
        UUID viewer = player.getUniqueId();
        clearVisibilityShowQueue(viewer);
        Set<VisualizerEntity> status = playerStatus.get(player);
        if (status == null || status.isEmpty()) {
            return;
        }
        for (VisualizerEntity logical : new HashSet<>(status)) {
            if (logical instanceof DisplayEntity display && virtualTextDisplays.containsKey(display)) {
                forgetShownViewer(display, viewer, player);
            } else if (logical instanceof Item item && packetOnlyItems.contains(item)) {
                forgetShownViewer(item, viewer, player);
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
        ChunkKey key = chunkByLogical.get(logical);
        Set<UUID> candidates = key == null ? Set.of() : viewerChunks.viewers(key);
        PerformanceMetrics.viewerReconcile(candidates.size());
        ViewerCullingManager culling = InteractionVisualizer.viewerCullingManager;
        Set<UUID> shown = shownViewers.get(logical);
        if (shown != null) {
            for (UUID viewer : shown) {
                Player player = Bukkit.getPlayer(viewer);
                if (!candidates.contains(viewer) || player == null || !isViewerDesired(logical, player)) {
                    culling.untrack(viewer, logical.getUniqueId());
                    hideViewer(logical, viewer, player);
                }
            }
        }
        Set<UUID> retainedCullViewers = usesCraftEngineCulling(logical)
                ? new HashSet<>() : Set.of();
        for (UUID viewer : candidates) {
            Player player = Bukkit.getPlayer(viewer);
            if (player != null && reconcileViewer(logical, player)) {
                retainedCullViewers.add(viewer);
            }
        }
        culling.retainLogical(logical.getUniqueId(), retainedCullViewers);
    }

    /** Returns true when the optional culling backend owns this candidate. */
    private static boolean reconcileViewer(VisualizerEntity logical, Player player) {
        UUID viewer = player.getUniqueId();
        if (!isViewerDesired(logical, player)) {
            InteractionVisualizer.viewerCullingManager.untrack(viewer, logical.getUniqueId());
            hideViewer(logical, viewer, player);
            return false;
        }
        if (usesCraftEngineCulling(logical)) {
            PerformanceMetrics.craftEngineCullingCandidate();
            if (InteractionVisualizer.viewerCullingManager.track(
                    player, logical.getUniqueId(), cullingBounds(logical))) {
                return true;
            }
        }
        Set<UUID> shown = shownViewers.get(logical);
        if (shown == null || !shown.contains(viewer)) {
            requestViewerShow(logical, player);
        }
        return false;
    }

    /** Called on the main thread after CraftEngine's async decisions are coalesced. */
    public static void onCullingVisibility(UUID viewer, UUID logicalId, boolean visible) {
        PerformanceMetrics.craftEngineCullingDecision(visible);
        runSync(() -> {
            VisualizerEntity logical = logicalById.get(logicalId);
            Player player = Bukkit.getPlayer(viewer);
            if (logical == null || player == null || !player.isOnline()) {
                InteractionVisualizer.viewerCullingManager.untrack(viewer, logicalId);
                return;
            }
            if (!isViewerDesired(logical, player)) {
                InteractionVisualizer.viewerCullingManager.untrack(viewer, logicalId);
                hideViewer(logical, viewer, player);
                return;
            }
            if (!InteractionVisualizer.viewerCullingManager.enabled()) {
                requestViewerShow(logical, player);
            } else if (visible) {
                requestViewerShow(logical, player);
            } else {
                hideViewer(logical, viewer, player);
            }
        });
    }

    /** Rebuilds only sent-chunk candidates after the optional backend changes on reload. */
    public static void onCullingBackendChanged() {
        runSync(() -> {
            for (VisualizerEntity logical : active.keySet()) {
                reconcileViewers(logical);
            }
        });
    }

    private static void remove(Collection<Player> players, VisualizerEntity logical, boolean removeFromActive) {
        if (removeFromActive) {
            clearRemovedLogicalState(logical);
        }
        runSync(() -> {
            if (removeFromActive) {
                // A sync that had already passed its active check can finish
                // between the caller-side cleanup and this main-thread task.
                // Clear the representation state again at the serialization point.
                clearRemovedLogicalState(logical);
                // Keep both sides of the chunk index on the main thread. An
                // asynchronous remove must not interleave unindex() with index().
                unindex(logical);
            }
            org.bukkit.entity.Entity actual = logical.getBukkitEntity().orElse(null);
            if (removeFromActive) {
                if (actual != null) {
                    discardActual(logical, actual);
                } else {
                    untrackActual(logical);
                    logical.unbind();
                    clearViewerTracking(logical);
                }
                if (logical instanceof DisplayEntity display) {
                    virtualTextDisplays.remove(display);
                }
            } else if (players != null) {
                for (Player player : players) {
                    if (player != null) {
                        hideViewer(logical, player.getUniqueId(), player);
                    }
                }
            }
        });
    }

    private static void clearRemovedLogicalState(VisualizerEntity logical) {
        active.remove(logical);
        logicalById.remove(logical.getUniqueId(), logical);
        InteractionVisualizer.viewerCullingManager.clearLogical(logical.getUniqueId());
        renderedRevision.remove(logical);
        forceScheduled.remove(logical);
        if (logical instanceof BillboardDisplayEntity billboard) {
            forgetDynamicEntity(billboard);
        }
        if (logical instanceof Item item) {
            itemAnimations.remove(item);
            renderedItemStacks.remove(item);
            renderedItemLocations.remove(item);
            packetItemPositions.remove(item);
            packetOnlyItems.remove(item);
            fixedItemDisplays.remove(item);
        }
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
        if (logical instanceof Item item && !fixedItemDisplays.contains(item)) {
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (dynamicInputChanged(event.getFrom(), event.getTo())) {
            markDynamicViewerDirty(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (dynamicInputChanged(event.getFrom(), event.getTo())) {
            markDynamicPassengersDirty(event.getVehicle());
        }
    }

    private static void markDynamicPassengersDirty(Entity vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof Player player) {
                markDynamicViewerDirty(player);
            } else {
                markDynamicPassengersDirty(passenger);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        forgetDynamicViewer(event.getPlayer().getUniqueId());
        forgetPacketOnlyViewer(event.getPlayer());
        resetViewerChunksAndSend(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        forgetDynamicViewer(event.getPlayer().getUniqueId());
        forgetPacketOnlyViewer(event.getPlayer());
        resetViewerChunksAndSend(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        forgetDynamicViewer(event.getPlayer().getUniqueId());
        forgetPacketOnlyViewer(event.getPlayer());
        resetViewerChunksAndSend(event.getPlayer());
    }

    @EventHandler
    public void onLeave(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        InteractionVisualizer.viewerCullingManager.clearViewer(uuid);
        forgetDynamicViewer(uuid);
        clearVisibilityShowQueue(uuid);
        viewerChunks.removeViewer(uuid);
        Set<VisualizerEntity> shown = shownByViewer.remove(uuid);
        if (shown != null) {
            for (VisualizerEntity logical : shown) {
                Set<UUID> viewers = shownViewers.get(logical);
                if (viewers != null) {
                    viewers.remove(uuid);
                    if (viewers.isEmpty()) {
                        shownViewers.remove(logical, viewers);
                    }
                }
                if (logical instanceof Item item) {
                // The connection is closing; discard bookkeeping without
                // sending a remove packet that cannot be observed.
                    forgetVirtualItem(item, uuid);
                }
            }
        }
        playerStatus.remove(event.getPlayer());
    }

    private static void resetViewerChunksAndSend(Player player) {
        UUID viewer = player.getUniqueId();
        InteractionVisualizer.viewerCullingManager.clearViewer(viewer);
        viewerChunks.removeViewer(viewer);
        Bukkit.getScheduler().runTask(plugin(), () -> {
            seedSentChunks(player);
            sendPlayerPackets(player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChunkLoad(PlayerChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        viewerChunks.add(event.getPlayer().getUniqueId(), key);
        Set<VisualizerEntity> logicals = logicalsByChunk.get(key);
        if (logicals == null) {
            return;
        }
        // This event fires after the chunk packet is sent and is Paper's
        // supported lifecycle hook for client-side entities. Reconcile both
        // packet-only items and Paper-owned displays here: a ChunkLoadEvent can
        // otherwise call showEntity() too early and lose an entire chunk's
        // display spawns. Chunk index values are concurrent sets, so no
        // snapshot allocation is necessary.
        for (VisualizerEntity logical : logicals) {
            reconcileViewer(logical, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChunkUnload(PlayerChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        viewerChunks.remove(event.getPlayer().getUniqueId(), key);
        Set<VisualizerEntity> logicals = logicalsByChunk.get(key);
        if (logicals == null) {
            return;
        }
        Player player = event.getPlayer();
        VirtualItemIdBuffer virtualItemIdsToRemove = new VirtualItemIdBuffer();
        for (VisualizerEntity logical : logicals) {
            InteractionVisualizer.viewerCullingManager.untrack(
                    player.getUniqueId(), logical.getUniqueId());
            if (logical instanceof Item item && packetOnlyItems.contains(item)) {
                Integer id = forgetShownViewer(item, player.getUniqueId(), player);
                if (id != null) {
                    virtualItemIdsToRemove.add(id);
                }
            } else {
                // Revoke visibleByDefault=false's Paper override while the
                // chunk is absent. This makes the next PlayerChunkLoadEvent the
                // single source of truth and lets its visibility token bucket
                // pace even entities whose server chunk stayed loaded.
                hideViewer(logical, player.getUniqueId(), player);
            }
        }
        removeVirtualItems(player, virtualItemIdsToRemove);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        ChunkKey key = new ChunkKey(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ());
        Set<VisualizerEntity> logicals = logicalsByChunk.get(key);
        if (logicals != null) {
            for (VisualizerEntity logical : logicals) {
                boolean missingRepresentation = logical instanceof DisplayEntity display && usesVirtualText(display)
                        ? !virtualTextDisplays.containsKey(display)
                        : logical.getBukkitEntity().isEmpty();
                if (active.containsKey(logical) && missingRepresentation) {
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
            logicalById.remove(logical.getUniqueId(), logical);
            renderedRevision.remove(logical);
            unindex(logical);
            renderedItemStacks.remove((Item) logical);
            renderedItemLocations.remove((Item) logical);
            packetOnlyItems.remove((Item) logical);
            fixedItemDisplays.remove((Item) logical);
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

    static final class VisibilityShowQueue<T> {

        private final LinkedHashSet<T> pending;
        private int tokens;
        private long lastRefillTick;

        VisibilityShowQueue(int initialTokens) {
            this(initialTokens, 0L);
        }

        VisibilityShowQueue(int initialTokens, long initialTick) {
            this.pending = new LinkedHashSet<>();
            this.tokens = Math.max(0, initialTokens);
            this.lastRefillTick = initialTick;
        }

        boolean request(T value) {
            return pending.add(value);
        }

        boolean cancel(T value) {
            return pending.remove(value);
        }

        List<T> drain(int capacity, int refill, Predicate<T> desired) {
            return drain(capacity, refill, lastRefillTick + 1L, desired);
        }

        List<T> drain(int capacity, int refill, long currentTick, Predicate<T> desired) {
            List<T> ready = new ArrayList<>();
            drainTo(capacity, refill, currentTick, value -> {
                if (!desired.test(value)) {
                    return false;
                }
                ready.add(value);
                return true;
            });
            return ready;
        }

        int drainTo(int capacity, int refill, long currentTick, DrainAction<T> action) {
            int boundedCapacity = Math.max(1, capacity);
            long elapsedTicks = Math.max(0L, currentTick - lastRefillTick);
            long restored = elapsedTicks * (long) Math.max(0, refill);
            tokens = (int) Math.min(boundedCapacity, Math.max(0L, tokens + restored));
            lastRefillTick = Math.max(lastRefillTick, currentTick);
            if (tokens == 0 || pending.isEmpty()) {
                return 0;
            }
            int drained = 0;
            int inspected = 0;
            while (tokens > 0 && inspected < boundedCapacity && !pending.isEmpty()) {
                T value = pending.removeFirst();
                inspected++;
                if (!action.accept(value)) {
                    continue;
                }
                tokens--;
                drained++;
            }
            return drained;
        }

        List<T> drainAll(Predicate<T> desired) {
            List<T> ready = new ArrayList<>();
            drainAllTo(value -> {
                if (!desired.test(value)) {
                    return false;
                }
                ready.add(value);
                return true;
            });
            return ready;
        }

        int drainAllTo(DrainAction<T> action) {
            int drained = 0;
            while (!pending.isEmpty()) {
                T value = pending.removeFirst();
                if (action.accept(value)) {
                    drained++;
                }
            }
            return drained;
        }

        boolean isEmpty() {
            return pending.isEmpty();
        }

        boolean hasPending() {
            return !pending.isEmpty();
        }

        void clear() {
            pending.clear();
        }

        void clear(Consumer<T> removed) {
            while (!pending.isEmpty()) {
                removed.accept(pending.removeFirst());
            }
        }

        @FunctionalInterface
        interface DrainAction<T> {

            boolean accept(T value);
        }

    }

    private static final class VirtualTextState {

        private final ClientTextDisplayBridge display;
        private Component text;
        private Location anchor;
        private double radius;
        private PathType path;

        private VirtualTextState(ClientTextDisplayBridge display, DisplayEntity logical) {
            this.display = display;
            this.text = Component.empty();
            this.anchor = null;
            this.radius = Double.NaN;
            this.path = null;
        }

        private boolean positionProfileChanged(DisplayEntity logical) {
            Location current = logical.getLocation();
            if (!sameLocation(anchor, current)) {
                return true;
            }
            if (logical instanceof BillboardDisplayEntity billboard) {
                return radius != billboard.getRadius() || path != billboard.getPathType();
            }
            return !Double.isNaN(radius) || path != null;
        }

        private void capture(DisplayEntity logical, Component text) {
            this.text = text;
            this.anchor = logical.getLocation();
            if (logical instanceof BillboardDisplayEntity billboard) {
                this.radius = billboard.getRadius();
                this.path = billboard.getPathType();
            } else {
                this.radius = Double.NaN;
                this.path = null;
            }
        }

        private static boolean sameLocation(Location first, Location second) {
            return first != null && second != null
                    && Objects.equals(first.getWorld(), second.getWorld())
                    && first.getX() == second.getX()
                    && first.getY() == second.getY()
                    && first.getZ() == second.getZ()
                    && first.getYaw() == second.getYaw()
                    && first.getPitch() == second.getPitch();
        }
    }

    static final class ViewerChunkIndex<V, C> {

        private final Map<V, Set<C>> chunksByViewer = new ConcurrentHashMap<>();
        private final Map<C, Set<V>> viewersByChunk = new ConcurrentHashMap<>();

        boolean add(V viewer, C chunk) {
            boolean added = chunksByViewer.computeIfAbsent(viewer,
                    ignored -> ConcurrentHashMap.newKeySet()).add(chunk);
            if (added) {
                viewersByChunk.computeIfAbsent(chunk,
                        ignored -> ConcurrentHashMap.newKeySet()).add(viewer);
            }
            return added;
        }

        boolean remove(V viewer, C chunk) {
            Set<C> chunks = chunksByViewer.get(viewer);
            if (chunks == null || !chunks.remove(chunk)) {
                return false;
            }
            if (chunks.isEmpty()) {
                chunksByViewer.remove(viewer, chunks);
            }
            Set<V> viewers = viewersByChunk.get(chunk);
            if (viewers != null) {
                viewers.remove(viewer);
                if (viewers.isEmpty()) {
                    viewersByChunk.remove(chunk, viewers);
                }
            }
            return true;
        }

        void removeViewer(V viewer) {
            Set<C> chunks = chunksByViewer.remove(viewer);
            if (chunks == null) {
                return;
            }
            for (C chunk : chunks) {
                Set<V> viewers = viewersByChunk.get(chunk);
                if (viewers != null) {
                    viewers.remove(viewer);
                    if (viewers.isEmpty()) {
                        viewersByChunk.remove(chunk, viewers);
                    }
                }
            }
        }

        boolean contains(V viewer, C chunk) {
            Set<C> chunks = chunksByViewer.get(viewer);
            return chunks != null && chunks.contains(chunk);
        }

        Set<C> chunks(V viewer) {
            Set<C> chunks = chunksByViewer.get(viewer);
            return chunks == null ? Set.of() : chunks;
        }

        Set<V> viewers(C chunk) {
            Set<V> viewers = viewersByChunk.get(chunk);
            return viewers == null ? Set.of() : viewers;
        }

        void clear() {
            chunksByViewer.clear();
            viewersByChunk.clear();
        }
    }

    private record DynamicViewerPosition(double x, double y, double z) {

        private static DynamicViewerPosition at(Location location) {
            return new DynamicViewerPosition(location.getX(), location.getY(), location.getZ());
        }

        private boolean matches(Location location) {
            return Math.abs(x - location.getX()) <= DYNAMIC_POSITION_EPSILON
                    && Math.abs(y - location.getY()) <= DYNAMIC_POSITION_EPSILON
                    && Math.abs(z - location.getZ()) <= DYNAMIC_POSITION_EPSILON;
        }
    }

    private static final class ItemAnimationState {

        private double velocityX;
        private double velocityY;
        private double velocityZ;
        private final boolean gravity;
        private final World world;
        private double positionX;
        private double positionY;
        private double positionZ;
        private final boolean staticAnchor;
        private final World logicalWorld;
        private final double logicalX;
        private final double logicalY;
        private final double logicalZ;

        private ItemAnimationState(Vector velocity, boolean gravity, Location position,
                                   boolean staticAnchor, Location logicalLocation) {
            this.velocityX = velocity.getX();
            this.velocityY = velocity.getY();
            this.velocityZ = velocity.getZ();
            this.gravity = gravity;
            this.world = Objects.requireNonNull(position.getWorld(), "position world");
            this.positionX = position.getX();
            this.positionY = position.getY();
            this.positionZ = position.getZ();
            this.staticAnchor = staticAnchor;
            this.logicalWorld = Objects.requireNonNull(logicalLocation.getWorld(), "logical world");
            this.logicalX = logicalLocation.getX();
            this.logicalY = logicalLocation.getY();
            this.logicalZ = logicalLocation.getZ();
        }

        private Location position() {
            return new Location(world, positionX, positionY, positionZ);
        }

        private Location logicalLocation() {
            return new Location(logicalWorld, logicalX, logicalY, logicalZ);
        }

        private Vector nextMovement() {
            return new Vector(velocityX,
                    velocityY - (gravity ? ITEM_GRAVITY_PER_TICK : 0.0D), velocityZ);
        }

        private double velocityLengthSquared() {
            return velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ;
        }
    }

    private static final class PacketItemPosition {

        private World world;
        private double x;
        private double y;
        private double z;

        private PacketItemPosition(World world, double x, double y, double z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private Location location() {
            return new Location(world, x, y, z);
        }
    }

    private static final class VirtualItemIdBuffer {

        private static final int[] EMPTY = new int[0];

        private int[] values = EMPTY;
        private int size;

        private void add(int value) {
            if (size == values.length) {
                int[] expanded = new int[Math.max(16, size << 1)];
                System.arraycopy(values, 0, expanded, 0, size);
                values = expanded;
            }
            values[size++] = value;
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private int size() {
            return size;
        }

        private void copyInto(int sourceOffset, int[] destination) {
            System.arraycopy(values, sourceOffset, destination, 0, destination.length);
        }
    }

    private record RepresentationKey(UUID actualUuid, boolean packetOnly, boolean virtualTextReady) {
    }

    private record ChunkKey(UUID world, int x, int z) {
    }
}
