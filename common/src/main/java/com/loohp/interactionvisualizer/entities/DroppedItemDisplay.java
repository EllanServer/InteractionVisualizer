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

package com.loohp.interactionvisualizer.entities;

import com.loohp.interactionvisualizer.InteractionVisualizer;
import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI;
import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI.Modules;
import com.loohp.interactionvisualizer.api.VisualizerRunnableDisplay;
import com.loohp.interactionvisualizer.api.events.InteractionVisualizerReloadEvent;
import com.loohp.interactionvisualizer.integration.CustomContentManager;
import com.loohp.interactionvisualizer.managers.PerformanceMetrics;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.utils.ChatColorUtils;
import com.loohp.interactionvisualizer.utils.ItemNameUtils;
import com.loohp.interactionvisualizer.scheduler.ScheduledRunnable;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Event-maintained dropped-item labels rendered as Paper TextDisplays.
 * With culling enabled, Paper's movement-maintained entity section index is
 * the candidate source; IV never rebuilds or scans a parallel world item map.
 */
public final class DroppedItemDisplay extends VisualizerRunnableDisplay implements Listener {

    public static final EntryKey KEY = new EntryKey("item");
    private static final int DEFAULT_TRACKING_DISTANCE = 64;
    static final int VIEW_DISTANCE_HYSTERESIS = 16;
    static final int OUTSIDE_CANDIDATE_RANGE = 0;
    static final int RETAINED_CANDIDATE = 1;
    static final int DESIRED_CANDIDATE = 2;

    private final Map<UUID, Item> trackedItems = new HashMap<>();
    private final Map<UUID, TextDisplay> labels = new HashMap<>();
    private final Map<UUID, TextDisplay> pendingRemovalLabels = new HashMap<>();
    private final Map<UUID, CachedItemContent> contentCache = new HashMap<>();
    private final Set<UUID> eligibleViewers = new HashSet<>();
    private final Map<UUID, Player> desiredViewers = new HashMap<>();
    private final Map<UUID, VisibilityState> visibilityStates = new HashMap<>();
    private final Map<UUID, Set<UUID>> viewersByLabel = new HashMap<>();
    private final Set<UUID> pendingVisibilityViewers = new HashSet<>();
    private final Set<UUID> candidateItems = new HashSet<>();
    private final Set<UUID> crampIndexedItems = new HashSet<>();
    private final DroppedItemSpatialIndex crampIndex = new DroppedItemSpatialIndex();
    private final NamespacedKey visualEntityKey;

    private String highColor = "";
    private String mediumColor = "";
    private String lowColor = "";
    private DroppedItemLabelFormatter labelFormatter;
    private int cramp = 6;
    private double labelYOffset = 0.8D;
    private int updateRate = 20;
    private int despawnTicks = 6000;
    private DroppedItemVisibilityPolicy visibilityPolicy = DroppedItemVisibilityPolicy.legacyDefaults();
    private boolean sourceOwnedSectionCandidates;
    private boolean stripColorBlacklist;
    private DroppedItemBlacklist blacklist = DroppedItemBlacklist.compile(List.of(), DroppedItemDisplay::warn);
    private ScheduledTask updateTask;
    private ScheduledTask visibilityDrainTask;
    private Runnable viewerGroupUnsubscribe;
    private volatile boolean closed;

    public DroppedItemDisplay() {
        visualEntityKey = new NamespacedKey(InteractionVisualizer.plugin, "visual_entity");
        onReload(new InteractionVisualizerReloadEvent());
    }

    @EventHandler
    public void onReload(InteractionVisualizerReloadEvent event) {
        if (closed) {
            return;
        }
        DroppedItemVisibilityPolicy previousVisibilityPolicy = visibilityPolicy;
        String regularFormatting = configString("Entities.Item.Options.RegularFormat");
        String singularFormatting = configString("Entities.Item.Options.SingularFormat");
        String toolsFormatting = configString("Entities.Item.Options.ToolsFormat");
        highColor = configString("Entities.Item.Options.Color.High");
        mediumColor = configString("Entities.Item.Options.Color.Medium");
        lowColor = configString("Entities.Item.Options.Color.Low");
        labelFormatter = new DroppedItemLabelFormatter(
                regularFormatting, singularFormatting, toolsFormatting,
                highColor, mediumColor, lowColor);
        cramp = InteractionVisualizer.plugin.getConfiguration().getInt("Entities.Item.Options.Cramping");
        double configuredLabelYOffset = InteractionVisualizer.plugin.getConfiguration()
                .getDouble("Entities.Item.Options.LabelYOffset");
        labelYOffset = Double.isFinite(configuredLabelYOffset) ? configuredLabelYOffset : 0.8D;
        updateRate = Math.max(1, InteractionVisualizer.plugin.getConfiguration().getInt("Entities.Item.Options.UpdateRate"));
        int configuredViewDistance = InteractionVisualizer.plugin.getConfiguration()
                .getInt("Entities.Item.Options.VisibilityCulling.ViewDistance");
        int configuredBucketSize = InteractionVisualizer.plugin.getConfiguration()
                .getInt("Entities.Item.Options.VisibilityRateLimit.BucketSize");
        int configuredRefill = InteractionVisualizer.plugin.getConfiguration()
                .getInt("Entities.Item.Options.VisibilityRateLimit.RestorePerTick");
        visibilityPolicy = DroppedItemVisibilityPolicy.create(
                InteractionVisualizer.plugin.getConfiguration()
                        .getBoolean("Entities.Item.Options.VisibilityCulling.Enabled"),
                configuredViewDistance,
                InteractionVisualizer.plugin.getConfiguration()
                        .getBoolean("Entities.Item.Options.VisibilityRateLimit.Enabled"),
                configuredBucketSize,
                configuredRefill);
        sourceOwnedSectionCandidates = InteractionVisualizer.plugin.getConfiguration().getBoolean(
                "Entities.Item.Options.VisibilityCulling.SourceOwnedSectionCandidates");
        PerformanceMetrics.droppedLabelCandidateSource(sourceOwnedSectionCandidates);
        PerformanceMetrics.droppedLabelVisibilityConfig(
                visibilityPolicy.cullingEnabled(),
                visibilityPolicy.viewDistance(),
                visibilityPolicy.rateLimitEnabled(),
                visibilityPolicy.bucketSize(),
                visibilityPolicy.restorePerTick());
        if (previousVisibilityPolicy.controlsPerViewerVisibility()
                != visibilityPolicy.controlsPerViewerVisibility()) {
            switchVisibilityMode(visibilityPolicy.controlsPerViewerVisibility());
        }
        int configuredDespawnTicks = InteractionVisualizer.plugin.getConfiguration().getInt("Entities.Item.Options.DespawnTicks");
        despawnTicks = configuredDespawnTicks > 0 ? configuredDespawnTicks : 6000;
        stripColorBlacklist = InteractionVisualizer.plugin.getConfiguration()
                .getBoolean("Entities.Item.Options.Blacklist.StripColorWhenMatching");
        blacklist = DroppedItemBlacklist.compile(
                InteractionVisualizer.plugin.getConfiguration().getList("Entities.Item.Options.Blacklist.List"),
                DroppedItemDisplay::warn);
        contentCache.clear();
        if (!trackedItems.isEmpty()) {
            wakeUpdate();
        }
    }

    private static String configString(String path) {
        String value = InteractionVisualizer.plugin.getConfiguration().getString(path);
        return ChatColorUtils.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private static void warn(String message) {
        Bukkit.getConsoleSender().sendMessage(Component.text(message, NamedTextColor.RED));
    }

    @Override
    public EntryKey key() {
        return KEY;
    }

    @Override
    public ScheduledTask gc() {
        return null;
    }

    @Override
    public ScheduledTask run() {
        if (closed) {
            return null;
        }
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (!isOwned(item)) {
                    track(item);
                }
            }
        }
        scheduleUpdate(1L);
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (closed) {
            return;
        }
        Item item = event.getEntity();
        if (!isOwned(item)) {
            track(item);
            wakeUpdate();
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (closed) {
            return;
        }
        boolean added = false;
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Item item && !isOwned(item)) {
                track(item);
                added = true;
            }
        }
        if (added) {
            wakeUpdate();
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        if (closed) {
            return;
        }
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Item item) {
                remove(item.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        if (closed) {
            return;
        }
        if (event.getEntity() instanceof Item item) {
            UUID itemId = item.getUniqueId();
            trackedItems.remove(itemId);
            contentCache.remove(itemId);
            TextDisplay label = labels.remove(itemId);
            if (label != null) {
                // EntityRemoveEvent is monitoring-only. Defer entity mutation
                // until Paper has finished removing the item's passengers.
                pendingRemovalLabels.put(itemId, label);
                Scheduler.runTask(InteractionVisualizer.plugin, () -> {
                    if (pendingRemovalLabels.remove(itemId, label)) {
                        forgetLabelVisibility(itemId, label);
                        removeLabel(label);
                    }
                });
            }
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (closed || trackedItems.isEmpty()) {
            return;
        }
        if (InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY, false)
                .contains(event.getPlayer())) {
            wakeUpdateImmediately();
        }
    }

    private void track(Item item) {
        if (!closed) {
            trackedItems.put(item.getUniqueId(), item);
        }
    }

    private void scheduleUpdate(long delay) {
        if (!closed && (updateTask == null || updateTask.isCancelled())) {
            updateTask = Scheduler.runTaskLater(
                    InteractionVisualizer.plugin, this::runScheduledUpdate, delay);
        }
    }

    private void wakeUpdate() {
        // Coalesce spawn/load/preference bursts. When the periodic pass is
        // already armed, its configured UpdateRate remains the visual SLA.
        scheduleUpdate(1L);
    }

    private void wakeUpdateImmediately() {
        if (closed) {
            return;
        }
        if (updateTask != null && !updateTask.isCancelled()) {
            updateTask.cancel();
        }
        updateTask = null;
        scheduleUpdate(1L);
    }

    private void runScheduledUpdate() {
        updateTask = null;
        if (closed) {
            return;
        }
        ensureViewerGroupListener();
        tickAll();
        boolean hasActiveViewerState = !desiredViewers.isEmpty()
                || !pendingVisibilityViewers.isEmpty() || !labels.isEmpty();
        boolean hasOnlineViewerGroupMember = !hasActiveViewerState
                && hasOnlineViewerGroupMember();
        if (shouldScheduleUpdate(!trackedItems.isEmpty(), viewerGroupUnsubscribe == null,
                hasActiveViewerState, hasOnlineViewerGroupMember)) {
            scheduleUpdate(updateRate);
        }
    }

    static boolean shouldScheduleUpdate(boolean hasTrackedItems,
                                        boolean viewerGroupListenerMissing,
                                        boolean hasActiveViewerState,
                                        boolean hasOnlineViewerGroupMember) {
        return hasTrackedItems && (viewerGroupListenerMissing
                || hasActiveViewerState || hasOnlineViewerGroupMember);
    }

    private boolean hasOnlineViewerGroupMember() {
        for (Player player : InteractionVisualizerAPI.getPlayerModuleList(
                Modules.HOLOGRAM, KEY, false)) {
            if (player.isOnline()) {
                return true;
            }
        }
        return false;
    }

    private void ensureViewerGroupListener() {
        if (closed || viewerGroupUnsubscribe != null
                || InteractionVisualizer.preferenceManager == null) {
            return;
        }
        viewerGroupUnsubscribe = InteractionVisualizer.preferenceManager
                .addViewerGroupChangeListener(Modules.HOLOGRAM, KEY,
                        this::onViewerGroupChanged);
    }

    private synchronized void onViewerGroupChanged() {
        if (!closed) {
            Scheduler.executeOrScheduleSync(InteractionVisualizer.plugin, this::wakeUpdate);
        }
    }

    private void scheduleVisibilityDrain() {
        if (closed || pendingVisibilityViewers.isEmpty()
                || visibilityDrainTask != null && !visibilityDrainTask.isCancelled()) {
            return;
        }
        visibilityDrainTask = Scheduler.runTaskLater(
                InteractionVisualizer.plugin, this::runVisibilityDrain, 1L);
    }

    private void runVisibilityDrain() {
        visibilityDrainTask = null;
        if (closed) {
            return;
        }
        drainVisibilityQueues();
        scheduleVisibilityDrain();
    }

    private void tickAll() {
        long started = PerformanceMetrics.isCollecting() ? System.nanoTime() : 0L;
        try {
            tickAllInternal();
        } finally {
            PerformanceMetrics.droppedItemState(trackedItems.size(), labels.size());
            if (started != 0L) {
                PerformanceMetrics.droppedItemNanos(System.nanoTime() - started);
            }
        }
    }

    private void tickAllInternal() {
        Collection<Player> viewers = reconcileEligibleViewers();
        if (viewers.isEmpty()) {
            removeAllLabels();
            return;
        }
        if (!sourceOwnedSectionCandidates || !visibilityPolicy.cullingEnabled()) {
            tickAllLegacy(viewers);
            return;
        }
        tickAllSectionCandidates(viewers);
    }

    private void tickAllSectionCandidates(Collection<Player> viewers) {
        candidateItems.clear();
        crampIndexedItems.clear();
        crampIndex.clear();
        if (visibilityPolicy.cullingEnabled()) {
            collectNearbyCandidates(viewers);
        } else {
            collectAllCandidates();
        }

        DroppedItemSpatialIndex itemIndex = cramp > 0 ? crampIndex : null;
        for (UUID itemId : candidateItems) {
            Item item = trackedItems.get(itemId);
            if (item != null && item.isValid() && !item.isDead()) {
                update(itemId, item, itemIndex);
            }
        }
        removeLabelsOutsideCandidates();
        if (visibilityPolicy.controlsPerViewerVisibility()) {
            reconcileLabelVisibility(viewers);
        }
    }

    private void tickAllLegacy(Collection<Player> viewers) {
        List<TrackedItem> validItems = new ArrayList<>(trackedItems.size());
        UUID singleItemWorldId = null;
        int singleWorldItemCount = 0;
        Map<UUID, Integer> itemCountsByWorld = null;
        Iterator<Map.Entry<UUID, Item>> iterator = trackedItems.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Item> entry = iterator.next();
            Item item = entry.getValue();
            if (!item.isValid() || item.isDead()) {
                iterator.remove();
                contentCache.remove(entry.getKey());
                removeLabel(entry.getKey());
                continue;
            }
            Location location = item.getLocation();
            UUID worldId = location.getWorld().getUID();
            validItems.add(new TrackedItem(entry.getKey(), item, worldId, location));
            if (singleItemWorldId == null) {
                singleItemWorldId = worldId;
                singleWorldItemCount = 1;
            } else if (itemCountsByWorld == null && singleItemWorldId.equals(worldId)) {
                singleWorldItemCount++;
            } else {
                if (itemCountsByWorld == null) {
                    itemCountsByWorld = new HashMap<>();
                    itemCountsByWorld.put(singleItemWorldId, singleWorldItemCount);
                }
                itemCountsByWorld.merge(worldId, 1, Integer::sum);
            }
        }
        PerformanceMetrics.droppedFullScanCandidates(validItems.size());

        DroppedItemSpatialIndex.ViewerIndex viewerIndex = null;
        if (visibilityPolicy.cullingEnabled()) {
            viewerIndex = new DroppedItemSpatialIndex.ViewerIndex(viewers.size());
            for (Player viewer : viewers) {
                Location location = viewer.getLocation();
                viewerIndex.addViewer(viewer.getWorld().getUID(),
                        location.getX(), location.getY(), location.getZ());
            }
        }

        DroppedItemSpatialIndex itemIndex = cramp > 0 ? new DroppedItemSpatialIndex() : null;
        if (itemIndex != null) {
            for (TrackedItem tracked : validItems) {
                Location location = tracked.location();
                itemIndex.addItem(tracked.worldId(),
                        location.getX(), location.getY(), location.getZ());
            }
        }
        int remainingSingleWorldItems = singleWorldItemCount;
        for (TrackedItem tracked : validItems) {
            int remainingWorldItems;
            if (itemCountsByWorld == null) {
                remainingWorldItems = --remainingSingleWorldItems;
            } else {
                remainingWorldItems = itemCountsByWorld.get(tracked.worldId()) - 1;
                itemCountsByWorld.put(tracked.worldId(), remainingWorldItems);
            }
            updateLegacy(tracked, itemIndex, viewerIndex, remainingWorldItems);
        }
        if (viewerIndex != null) {
            PerformanceMetrics.droppedViewerDistanceChecks(viewerIndex.candidateChecks());
        }
        if (visibilityPolicy.controlsPerViewerVisibility()) {
            DroppedItemVisibilityIndex<UUID> visibilityIndex = null;
            if (visibilityPolicy.cullingEnabled()) {
                visibilityIndex = new DroppedItemVisibilityIndex<>();
                for (TrackedItem tracked : validItems) {
                    TextDisplay label = labels.get(tracked.itemId());
                    if (label != null && label.isValid()) {
                        Location location = tracked.location();
                        visibilityIndex.add(tracked.worldId(), location.getX(), location.getY(),
                                location.getZ(), tracked.itemId());
                    }
                }
            }
            reconcileLabelVisibilityLegacy(viewers, validItems, visibilityIndex);
        }
    }

    private void updateLegacy(TrackedItem tracked, DroppedItemSpatialIndex itemIndex,
                              DroppedItemSpatialIndex.ViewerIndex viewerIndex,
                              int remainingWorldItems) {
        Item item = tracked.item();
        Location itemLocation = tracked.location();
        TextDisplay label = labels.get(tracked.itemId());
        if (viewerIndex != null) {
            int trackingDistance = InteractionVisualizer.playerTrackingRange
                    .getOrDefault(item.getWorld(), DEFAULT_TRACKING_DISTANCE);
            int effectiveViewDistance = visibilityPolicy.effectiveViewDistance(trackingDistance);
            int cullingDistance = label == null
                    ? effectiveViewDistance
                    : effectiveViewDistance + VIEW_DISTANCE_HYSTERESIS;
            if (!viewerIndex.hasViewerWithin(tracked.worldId(),
                    itemLocation.getX(), itemLocation.getY(), itemLocation.getZ(),
                    cullingDistance, remainingWorldItems)) {
                removeLabel(tracked.itemId());
                return;
            }
        }
        update(tracked.itemId(), item, itemIndex);
    }

    private void collectNearbyCandidates(Collection<Player> viewers) {
        for (Player player : viewers) {
            UUID playerId = player.getUniqueId();
            VisibilityState state = visibilityStates.computeIfAbsent(playerId,
                    ignored -> new VisibilityState(visibilityPolicy.bucketSize()));
            Set<UUID> desired = state.nextDesired;
            desired.clear();

            World world = player.getWorld();
            Location viewerLocation = player.getLocation();
            int trackingDistance = InteractionVisualizer.playerTrackingRange
                    .getOrDefault(world, DEFAULT_TRACKING_DISTANCE);
            double viewDistance = visibilityPolicy.effectiveViewDistance(trackingDistance);
            double queryDistance = sourceQueryDistance(viewDistance, cramp > 0);
            Collection<Item> nearby = world.getNearbyEntitiesByType(
                    Item.class, viewerLocation, queryDistance,
                    item -> item.isValid() && !item.isDead() && !isOwned(item));
            PerformanceMetrics.droppedSpatialCandidates(nearby.size());
            PerformanceMetrics.droppedViewerDistanceChecks(nearby.size());

            double viewerX = viewerLocation.getX();
            double viewerY = viewerLocation.getY();
            double viewerZ = viewerLocation.getZ();
            for (Item item : nearby) {
                UUID itemId = item.getUniqueId();
                track(item);
                indexCrampItem(itemId, item);
                double deltaX = item.getX() - viewerX;
                double deltaY = item.getY() - viewerY;
                double deltaZ = item.getZ() - viewerZ;
                int classification = classifyCandidate(
                        deltaX, deltaY, deltaZ, viewDistance, labels.containsKey(itemId));
                if (classification == DESIRED_CANDIDATE) {
                    desired.add(itemId);
                    candidateItems.add(itemId);
                } else if (classification == RETAINED_CANDIDATE) {
                    candidateItems.add(itemId);
                }
            }
        }
    }

    static double sourceQueryDistance(double viewDistance, boolean cramping) {
        return viewDistance + VIEW_DISTANCE_HYSTERESIS + (cramping ? 0.5D : 0.0D);
    }

    static int classifyCandidate(double deltaX, double deltaY, double deltaZ,
                                 double viewDistance, boolean hasLabel) {
        double distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        if (distanceSquared <= viewDistance * viewDistance) {
            return DESIRED_CANDIDATE;
        }
        double retainedDistance = viewDistance + VIEW_DISTANCE_HYSTERESIS;
        if (hasLabel && distanceSquared <= retainedDistance * retainedDistance) {
            return RETAINED_CANDIDATE;
        }
        return OUTSIDE_CANDIDATE_RANGE;
    }

    private void collectAllCandidates() {
        PerformanceMetrics.droppedFullScanCandidates(trackedItems.size());
        Iterator<Map.Entry<UUID, Item>> iterator = trackedItems.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Item> entry = iterator.next();
            UUID itemId = entry.getKey();
            Item item = entry.getValue();
            if (!item.isValid() || item.isDead()) {
                iterator.remove();
                contentCache.remove(itemId);
                removeLabel(itemId);
                continue;
            }
            candidateItems.add(itemId);
            indexCrampItem(itemId, item);
        }
    }

    private void indexCrampItem(UUID itemId, Item item) {
        if (cramp <= 0 || !crampIndexedItems.add(itemId)) {
            return;
        }
        crampIndex.addItem(item.getWorld().getUID(), item.getX(), item.getY(), item.getZ());
    }

    private void removeLabelsOutsideCandidates() {
        Iterator<Map.Entry<UUID, TextDisplay>> iterator = labels.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TextDisplay> entry = iterator.next();
            if (candidateItems.contains(entry.getKey())) {
                continue;
            }
            iterator.remove();
            forgetLabelVisibility(entry.getKey(), entry.getValue());
            removeLabel(entry.getValue());
        }
    }

    private void update(UUID itemId, Item item, DroppedItemSpatialIndex itemIndex) {
        UUID worldId = item.getWorld().getUID();
        double itemX = item.getX();
        double itemY = item.getY();
        double itemZ = item.getZ();
        TextDisplay label = labels.get(itemId);

        ItemStack stack = item.getItemStack();
        int ticksLeft = despawnTicks - item.getTicksLived();
        if (stack.isEmpty() || item.getPickupDelay() >= Short.MAX_VALUE || ticksLeft <= 0
                || (itemIndex != null && itemIndex.exceedsItemLimit(
                worldId, itemX, itemY, itemZ, cramp))) {
            contentCache.remove(itemId);
            removeLabel(itemId);
            return;
        }
        CachedItemContent content = cachedContent(itemId, stack);
        if (content.blacklisted) {
            removeLabel(itemId);
            return;
        }

        Component text = labelFormatter.format(content.formatState, ticksLeft);
        boolean created = false;
        if (label == null || !label.isValid() || !label.getWorld().equals(item.getWorld())) {
            removeLabel(itemId);
            label = spawnLabel(item);
            labels.put(itemId, label);
            created = true;
        }
        // The formatter returns the same immutable component instance while
        // the rendered state is unchanged. Avoid a CraftTextDisplay read on
        // every refresh; it serializes the NMS component back through Paper.
        int labelId = label.getEntityId();
        if (content.appliedLabelId != labelId || content.appliedText != text) {
            label.text(text);
            content.appliedLabelId = labelId;
            content.appliedText = text;
        }
        float targetViewRange = labelViewRange();
        if (Math.abs(label.getViewRange() - targetViewRange) > 1.0E-4F) {
            label.setViewRange(targetViewRange);
        }
        boolean mounted = item.equals(label.getVehicle()) || item.addPassenger(label);
        if (mounted) {
            // A mounted display follows the item on every client render frame.
            // Text refreshes stay low-frequency without sampling item positions.
            setLabelVerticalTranslation(label, mountedLabelTranslation(labelYOffset, item.getHeight()));
            if (label.getInterpolationDuration() != 0) {
                label.setInterpolationDuration(0);
            }
            if (label.getTeleportDuration() != 0) {
                label.setTeleportDuration(0);
            }
        } else {
            // Preserve a safe fallback if another plugin cancels the mount or
            // changes either entity during the update.
            setLabelVerticalTranslation(label, 0.0F);
            int transitionTicks = Math.min(59, updateRate);
            if (label.getTeleportDuration() != transitionTicks) {
                label.setTeleportDuration(transitionTicks);
            }
            label.teleport(labelLocation(item));
        }
        if (created && !visibilityPolicy.controlsPerViewerVisibility()) {
            showToEligibleViewers(label);
        }
    }

    private TextDisplay spawnLabel(Item item) {
        PerformanceMetrics.bukkitEntitySpawn();
        return item.getWorld().spawn(labelLocation(item),
                TextDisplay.class, display -> {
                    display.setPersistent(false);
                    display.setVisibleByDefault(false);
                    display.setGravity(false);
                    display.setInvulnerable(true);
                    display.setSilent(true);
                    display.setNoPhysics(true);
                    display.setBillboard(Display.Billboard.CENTER);
                    display.setViewRange(labelViewRange());
                    display.setInterpolationDuration(0);
                    display.setTeleportDuration(0);
                    display.setShadowed(true);
                    display.setSeeThrough(false);
                    display.setDefaultBackground(false);
                    display.setBackgroundColor(Color.fromARGB(0));
                    display.setAlignment(TextDisplay.TextAlignment.CENTER);
                    display.setLineWidth(240);
                    display.getPersistentDataContainer().set(
                            visualEntityKey, PersistentDataType.STRING, "dropped_item_label");
                });
    }

    private float labelViewRange() {
        return visibilityPolicy.labelViewRange();
    }

    private Location labelLocation(Item item) {
        return item.getLocation().add(0.0, labelYOffset, 0.0);
    }

    static float mountedLabelTranslation(double yOffset, double itemHeight) {
        return (float) (yOffset - itemHeight);
    }

    private static void setLabelVerticalTranslation(TextDisplay label, float targetY) {
        Transformation current = label.getTransformation();
        Vector3f translation = current.getTranslation();
        if (Math.abs(translation.y - targetY) <= 1.0E-4F) {
            return;
        }
        label.setTransformation(new Transformation(
                new Vector3f(translation.x, targetY, translation.z),
                new Quaternionf(current.getLeftRotation()),
                new Vector3f(current.getScale()),
                new Quaternionf(current.getRightRotation())));
    }

    private Collection<Player> reconcileEligibleViewers() {
        desiredViewers.clear();
        for (Player player : InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY)) {
            if (player.isOnline()) {
                desiredViewers.put(player.getUniqueId(), player);
            }
        }
        Iterator<UUID> eligibleIterator = eligibleViewers.iterator();
        while (eligibleIterator.hasNext()) {
            UUID uuid = eligibleIterator.next();
            if (!desiredViewers.containsKey(uuid)) {
                Player player = Bukkit.getPlayer(uuid);
                if (visibilityPolicy.controlsPerViewerVisibility()) {
                    removeVisibilityState(uuid, player);
                } else if (player != null) {
                    setAllLabelsVisible(player, false);
                }
                eligibleIterator.remove();
            }
        }
        for (Map.Entry<UUID, Player> entry : desiredViewers.entrySet()) {
            if (eligibleViewers.add(entry.getKey())
                    && !visibilityPolicy.controlsPerViewerVisibility()) {
                setAllLabelsVisible(entry.getValue(), true);
            }
        }
        return desiredViewers.values();
    }

    private void switchVisibilityMode(boolean controlled) {
        for (UUID playerId : eligibleViewers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                setAllLabelsVisible(player, !controlled);
            }
        }
        for (VisibilityState state : visibilityStates.values()) {
            state.pending.clear();
        }
        visibilityStates.clear();
        viewersByLabel.clear();
        pendingVisibilityViewers.clear();
    }

    private void showToEligibleViewers(TextDisplay label) {
        for (UUID playerId : eligibleViewers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                setLabelVisible(player, label, true);
            }
        }
    }

    private void setAllLabelsVisible(Player player, boolean visible) {
        for (TextDisplay label : labels.values()) {
            if (label.isValid()) {
                setLabelVisible(player, label, visible);
            }
        }
    }

    private static void setLabelVisible(Player player, TextDisplay label, boolean visible) {
        if (visible) {
            PerformanceMetrics.bukkitShow();
            player.showEntity(InteractionVisualizer.plugin, label);
        } else {
            PerformanceMetrics.bukkitHide();
            player.hideEntity(InteractionVisualizer.plugin, label);
        }
    }

    private void reconcileLabelVisibilityLegacy(Collection<Player> viewers,
                                                List<TrackedItem> validItems,
                                                DroppedItemVisibilityIndex<UUID> visibilityIndex) {
        for (Player player : viewers) {
            UUID playerId = player.getUniqueId();
            VisibilityState state = visibilityStates.computeIfAbsent(playerId,
                    ignored -> new VisibilityState(visibilityPolicy.bucketSize()));
            Set<UUID> desired = state.nextDesired;
            desired.clear();
            Location playerLocation = player.getLocation();
            int trackingDistance = InteractionVisualizer.playerTrackingRange
                    .getOrDefault(player.getWorld(), DEFAULT_TRACKING_DISTANCE);
            if (visibilityIndex != null) {
                double range = visibilityPolicy.effectiveViewDistance(trackingDistance);
                int candidates = visibilityIndex.queryInto(player.getWorld().getUID(),
                        playerLocation.getX(), playerLocation.getY(), playerLocation.getZ(), range, desired);
                PerformanceMetrics.droppedSpatialCandidates(candidates);
            } else {
                PerformanceMetrics.droppedFullScanCandidates(validItems.size());
                for (TrackedItem tracked : validItems) {
                    TextDisplay label = labels.get(tracked.itemId());
                    if (label != null && label.isValid()
                            && tracked.item().getWorld().equals(player.getWorld())) {
                        desired.add(tracked.itemId());
                    }
                }
            }
            finishLabelVisibility(player, state, desired);
        }
        scheduleVisibilityDrain();
    }

    private void reconcileLabelVisibility(Collection<Player> viewers) {
        for (Player player : viewers) {
            UUID playerId = player.getUniqueId();
            VisibilityState state = visibilityStates.computeIfAbsent(playerId,
                    ignored -> new VisibilityState(visibilityPolicy.bucketSize()));
            Set<UUID> desired = state.nextDesired;
            if (visibilityPolicy.cullingEnabled()) {
                Iterator<UUID> desiredIterator = desired.iterator();
                while (desiredIterator.hasNext()) {
                    TextDisplay label = labels.get(desiredIterator.next());
                    if (label == null || !label.isValid()) {
                        desiredIterator.remove();
                    }
                }
            } else {
                desired.clear();
                PerformanceMetrics.droppedFullScanCandidates(labels.size());
                for (Map.Entry<UUID, TextDisplay> entry : labels.entrySet()) {
                    TextDisplay label = entry.getValue();
                    if (label.isValid() && label.getWorld().equals(player.getWorld())) {
                        desired.add(entry.getKey());
                    }
                }
            }

            finishLabelVisibility(player, state, desired);
        }
        scheduleVisibilityDrain();
    }

    private void finishLabelVisibility(Player player, VisibilityState state, Set<UUID> desired) {
        UUID playerId = player.getUniqueId();
        Iterator<UUID> shownIterator = state.shown.iterator();
        while (shownIterator.hasNext()) {
            UUID itemId = shownIterator.next();
            if (!desired.contains(itemId)) {
                TextDisplay label = labels.get(itemId);
                if (label != null && label.isValid()) {
                    setLabelVisible(player, label, false);
                }
                shownIterator.remove();
            }
        }
        for (UUID itemId : state.desired) {
            if (!desired.contains(itemId)) {
                state.pending.cancel(itemId);
                unlinkLabelViewer(itemId, playerId);
            }
        }
        state.nextDesired = state.desired;
        state.desired = desired;
        for (UUID itemId : desired) {
            linkLabelViewer(itemId, playerId);
            if (!state.shown.contains(itemId)) {
                state.pending.request(itemId);
                pendingVisibilityViewers.add(playerId);
            }
        }
    }

    private void drainVisibilityQueues() {
        if (pendingVisibilityViewers.isEmpty()) {
            return;
        }
        Iterator<UUID> pendingViewers = pendingVisibilityViewers.iterator();
        while (pendingViewers.hasNext()) {
            UUID playerId = pendingViewers.next();
            Player player = Bukkit.getPlayer(playerId);
            VisibilityState state = visibilityStates.get(playerId);
            if (state == null || player == null || !player.isOnline() || !eligibleViewers.contains(playerId)) {
                if (state != null) {
                    state.pending.clear();
                }
                pendingViewers.remove();
                continue;
            }
            if (!state.pending.hasPending()) {
                pendingViewers.remove();
                continue;
            }
            List<UUID> ready = state.ready;
            ready.clear();
            if (visibilityPolicy.rateLimitEnabled()) {
                state.pending.drainInto(
                        visibilityPolicy.bucketSize(), visibilityPolicy.restorePerTick(),
                        id -> isPendingVisibilityWanted(state, id), ready);
            } else {
                state.pending.drainAllInto(id -> isPendingVisibilityWanted(state, id), ready);
            }
            for (UUID itemId : ready) {
                TextDisplay label = labels.get(itemId);
                if (label != null && label.isValid()) {
                    setLabelVisible(player, label, true);
                    state.shown.add(itemId);
                }
            }
            ready.clear();
            if (!state.pending.hasPending()) {
                pendingViewers.remove();
            }
        }
    }

    private boolean isPendingVisibilityWanted(VisibilityState state, UUID itemId) {
        TextDisplay label = labels.get(itemId);
        return state.desired.contains(itemId) && !state.shown.contains(itemId)
                && label != null && label.isValid();
    }

    private void removeVisibilityState(UUID playerId, Player player) {
        VisibilityState state = visibilityStates.remove(playerId);
        if (state == null) {
            return;
        }
        if (player != null) {
            for (UUID itemId : state.shown) {
                TextDisplay label = labels.get(itemId);
                if (label != null && label.isValid()) {
                    setLabelVisible(player, label, false);
                }
            }
        }
        for (UUID itemId : state.desired) {
            unlinkLabelViewer(itemId, playerId);
        }
        for (UUID itemId : state.shown) {
            unlinkLabelViewer(itemId, playerId);
        }
        state.pending.clear();
        pendingVisibilityViewers.remove(playerId);
    }

    private CachedItemContent cachedContent(UUID itemId, ItemStack stack) {
        CachedItemContent cached = contentCache.get(itemId);
        if (cached != null && cached.stack.equals(stack)) {
            return cached;
        }
        String matchingName = matchingName(stack);
        NamespacedKey customItemId = blacklist.requiresCustomItemId()
                ? CustomContentManager.customItemId(stack).orElse(null)
                : null;
        String durability = durability(stack);
        Component itemName = ItemNameUtils.getDisplayName(stack);
        CachedItemContent replacement = new CachedItemContent(
                stack.clone(),
                blacklist.matches(matchingName, stack.getType(), customItemId),
                labelFormatter.state(stack.getAmount(), durability, itemName));
        contentCache.put(itemId, replacement);
        return replacement;
    }

    private String durability(ItemStack stack) {
        if (stack.getType().getMaxDurability() <= 0 || !(stack.getItemMeta() instanceof Damageable damageable)) {
            return null;
        }
        int maximum = stack.getType().getMaxDurability();
        int remaining = maximum - damageable.getDamage();
        double percentage = (double) remaining / maximum;
        String color = percentage > 2.0 / 3.0 ? highColor : percentage > 1.0 / 3.0 ? mediumColor : lowColor;
        return color + remaining + "/" + maximum;
    }

    private String matchingName(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || meta.displayName() == null) {
            return "";
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        return stripColorBlacklist ? ChatColorUtils.stripColor(plain) : plain;
    }

    private void remove(UUID itemId) {
        trackedItems.remove(itemId);
        contentCache.remove(itemId);
        removeLabel(itemId);
    }

    private void removeLabel(UUID itemId) {
        TextDisplay label = labels.remove(itemId);
        forgetLabelVisibility(itemId, label);
        removeLabel(label);
    }

    private void removeAllLabels() {
        Iterator<Map.Entry<UUID, TextDisplay>> iterator = labels.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TextDisplay> entry = iterator.next();
            iterator.remove();
            forgetLabelVisibility(entry.getKey(), entry.getValue());
            removeLabel(entry.getValue());
        }
    }

    private void forgetLabelVisibility(UUID itemId, TextDisplay label) {
        Set<UUID> viewers = viewersByLabel.remove(itemId);
        if (viewers == null) {
            return;
        }
        for (UUID viewer : viewers) {
            VisibilityState state = visibilityStates.get(viewer);
            if (state == null) {
                continue;
            }
            state.desired.remove(itemId);
            state.nextDesired.remove(itemId);
            state.pending.cancel(itemId);
            if (state.shown.remove(itemId) && label != null && label.isValid()) {
                Player player = Bukkit.getPlayer(viewer);
                if (player != null) {
                    setLabelVisible(player, label, false);
                }
            }
            if (!state.pending.hasPending()) {
                pendingVisibilityViewers.remove(viewer);
            }
        }
    }

    private void linkLabelViewer(UUID itemId, UUID viewer) {
        viewersByLabel.computeIfAbsent(itemId, ignored -> new HashSet<>()).add(viewer);
    }

    private void unlinkLabelViewer(UUID itemId, UUID viewer) {
        Set<UUID> viewers = viewersByLabel.get(itemId);
        if (viewers != null) {
            viewers.remove(viewer);
            if (viewers.isEmpty()) {
                viewersByLabel.remove(itemId);
            }
        }
    }

    private void removeLabel(TextDisplay label) {
        if (label != null && label.isValid()) {
            if (!visibilityPolicy.controlsPerViewerVisibility()) {
                for (UUID playerId : eligibleViewers) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null) {
                        setLabelVisible(player, label, false);
                    }
                }
            }
            PerformanceMetrics.bukkitEntityRemove();
            label.remove();
        }
    }

    @Override
    protected void onUnregister() {
        closed = true;
        ScheduledTask pendingUpdate = updateTask;
        ScheduledTask pendingVisibilityDrain = visibilityDrainTask;
        Runnable unsubscribe = viewerGroupUnsubscribe;
        updateTask = null;
        visibilityDrainTask = null;
        viewerGroupUnsubscribe = null;

        Throwable failure = null;
        if (pendingUpdate != null) {
            try {
                pendingUpdate.cancel();
            } catch (Throwable throwable) {
                failure = appendFailure(failure, throwable);
            }
        }
        if (pendingVisibilityDrain != null) {
            try {
                pendingVisibilityDrain.cancel();
            } catch (Throwable throwable) {
                failure = appendFailure(failure, throwable);
            }
        }
        if (unsubscribe != null) {
            try {
                unsubscribe.run();
            } catch (Throwable throwable) {
                failure = appendFailure(failure, throwable);
            }
        }
        try {
            HandlerList.unregisterAll(this);
        } catch (Throwable throwable) {
            failure = appendFailure(failure, throwable);
        }
        try {
            for (Map.Entry<UUID, TextDisplay> entry : new ArrayList<>(labels.entrySet())) {
                failure = removeLabelOnUnregister(entry.getKey(), entry.getValue(), failure);
            }
            for (Map.Entry<UUID, TextDisplay> entry : new ArrayList<>(pendingRemovalLabels.entrySet())) {
                failure = removeLabelOnUnregister(entry.getKey(), entry.getValue(), failure);
            }
        } finally {
            trackedItems.clear();
            labels.clear();
            pendingRemovalLabels.clear();
            contentCache.clear();
            eligibleViewers.clear();
            desiredViewers.clear();
            visibilityStates.clear();
            viewersByLabel.clear();
            pendingVisibilityViewers.clear();
            candidateItems.clear();
            crampIndexedItems.clear();
            crampIndex.clear();
        }
        rethrow(failure);
    }

    private Throwable removeLabelOnUnregister(UUID itemId, TextDisplay label, Throwable failure) {
        if (label == null) {
            return failure;
        }
        Set<UUID> viewers = new HashSet<>();
        if (visibilityPolicy.controlsPerViewerVisibility()) {
            for (Map.Entry<UUID, VisibilityState> entry : visibilityStates.entrySet()) {
                if (entry.getValue().shown.contains(itemId)) {
                    viewers.add(entry.getKey());
                }
            }
        } else {
            viewers.addAll(eligibleViewers);
        }
        for (UUID viewerId : viewers) {
            try {
                Player player = Bukkit.getPlayer(viewerId);
                if (player != null && label.isValid()) {
                    setLabelVisible(player, label, false);
                }
            } catch (Throwable throwable) {
                failure = appendFailure(failure, throwable);
            }
        }
        try {
            PerformanceMetrics.bukkitEntityRemove();
            label.remove();
        } catch (Throwable throwable) {
            failure = appendFailure(failure, throwable);
        }
        return failure;
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

    private boolean isOwned(Entity entity) {
        return entity.getPersistentDataContainer().has(visualEntityKey, PersistentDataType.STRING);
    }

    private record TrackedItem(UUID itemId, Item item, UUID worldId, Location location) {
    }

    private static final class CachedItemContent {

        private final ItemStack stack;
        private final boolean blacklisted;
        private final DroppedItemLabelFormatter.State formatState;
        private int appliedLabelId = Integer.MIN_VALUE;
        private Component appliedText;

        private CachedItemContent(ItemStack stack, boolean blacklisted,
                                  DroppedItemLabelFormatter.State formatState) {
            this.stack = stack;
            this.blacklisted = blacklisted;
            this.formatState = formatState;
        }
    }

    private static final class VisibilityState {

        private Set<UUID> desired = new HashSet<>();
        private Set<UUID> nextDesired = new HashSet<>();
        private final Set<UUID> shown = new HashSet<>();
        private final VisibilityTokenBucket<UUID> pending;
        private final List<UUID> ready = new ArrayList<>();

        private VisibilityState(int initialTokens) {
            this.pending = new VisibilityTokenBucket<>(initialTokens);
        }
    }
}
