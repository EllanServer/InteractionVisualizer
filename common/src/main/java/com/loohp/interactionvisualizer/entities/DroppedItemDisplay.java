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
import com.loohp.interactionvisualizer.utils.LegacyTextComponentCache;
import com.loohp.interactionvisualizer.scheduler.ScheduledRunnable;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
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
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
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
 * One low-frequency pass over known items replaces world entity scans and
 * per-player metadata packet rewriting.
 */
public final class DroppedItemDisplay extends VisualizerRunnableDisplay implements Listener {

    public static final EntryKey KEY = new EntryKey("item");
    private static final int DEFAULT_TRACKING_DISTANCE = 64;
    private static final int VIEW_DISTANCE_HYSTERESIS = 16;

    private final Map<UUID, Item> trackedItems = new HashMap<>();
    private final Map<UUID, TextDisplay> labels = new HashMap<>();
    private final Set<UUID> eligibleViewers = new HashSet<>();
    private final Map<UUID, Player> desiredViewers = new HashMap<>();
    private final Map<UUID, VisibilityState> visibilityStates = new HashMap<>();

    private String regularFormatting;
    private String singularFormatting;
    private String toolsFormatting;
    private String highColor = "";
    private String mediumColor = "";
    private String lowColor = "";
    private int cramp = 6;
    private double labelYOffset = 0.8D;
    private int updateRate = 20;
    private int ticksUntilUpdate;
    private int despawnTicks = 6000;
    private DroppedItemVisibilityPolicy visibilityPolicy = DroppedItemVisibilityPolicy.legacyDefaults();
    private boolean visibilityQueuesPending;
    private boolean stripColorBlacklist;
    private DroppedItemBlacklist blacklist = DroppedItemBlacklist.compile(List.of(), DroppedItemDisplay::warn);

    public DroppedItemDisplay() {
        onReload(new InteractionVisualizerReloadEvent());
    }

    @EventHandler
    public void onReload(InteractionVisualizerReloadEvent event) {
        DroppedItemVisibilityPolicy previousVisibilityPolicy = visibilityPolicy;
        regularFormatting = configString("Entities.Item.Options.RegularFormat");
        singularFormatting = configString("Entities.Item.Options.SingularFormat");
        toolsFormatting = configString("Entities.Item.Options.ToolsFormat");
        highColor = configString("Entities.Item.Options.Color.High");
        mediumColor = configString("Entities.Item.Options.Color.Medium");
        lowColor = configString("Entities.Item.Options.Color.Low");
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
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (!isOwned(item)) {
                    track(item);
                }
            }
        }
        return new ScheduledRunnable() {
            @Override
            public void run() {
                if (visibilityPolicy.controlsPerViewerVisibility()) {
                    drainVisibilityQueues();
                }
                if (--ticksUntilUpdate <= 0) {
                    ticksUntilUpdate = updateRate;
                    tickAll();
                }
            }
        }.runTaskTimer(InteractionVisualizer.plugin, 1, 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        if (!isOwned(item)) {
            track(item);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Item item && !isOwned(item)) {
                track(item);
            }
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Item item) {
                remove(item.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        if (event.getEntity() instanceof Item item) {
            UUID itemId = item.getUniqueId();
            trackedItems.remove(itemId);
            TextDisplay label = labels.remove(itemId);
            if (label != null) {
                // EntityRemoveEvent is monitoring-only. Defer entity mutation
                // until Paper has finished removing the item's passengers.
                Scheduler.runTask(InteractionVisualizer.plugin, () -> {
                    forgetLabelVisibility(itemId, label);
                    removeLabel(label);
                });
            }
        }
    }

    private void track(Item item) {
        trackedItems.put(item.getUniqueId(), item);
    }

    private void tickAll() {
        long started = PerformanceMetrics.isCollecting() ? System.nanoTime() : 0L;
        try {
            tickAllInternal();
        } finally {
            if (started != 0L) {
                PerformanceMetrics.droppedItemNanos(System.nanoTime() - started);
            }
        }
    }

    private void tickAllInternal() {
        Collection<Player> viewers = reconcileEligibleViewers();
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

        if (viewers.isEmpty() && visibilityPolicy.cullingEnabled()) {
            for (UUID itemId : new HashSet<>(labels.keySet())) {
                removeLabel(itemId);
            }
            return;
        }

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
        for (int trackedIndex = 0; trackedIndex < validItems.size(); trackedIndex++) {
            TrackedItem tracked = validItems.get(trackedIndex);
            int remainingWorldItems;
            if (itemCountsByWorld == null) {
                remainingWorldItems = --remainingSingleWorldItems;
            } else {
                remainingWorldItems = itemCountsByWorld.get(tracked.worldId()) - 1;
                itemCountsByWorld.put(tracked.worldId(), remainingWorldItems);
            }
            update(tracked, itemIndex, viewerIndex, remainingWorldItems);
        }
        if (visibilityPolicy.controlsPerViewerVisibility()) {
            reconcileLabelVisibility(viewers, validItems);
        }
    }

    private void update(TrackedItem tracked, DroppedItemSpatialIndex itemIndex,
                        DroppedItemSpatialIndex.ViewerIndex viewerIndex, int remainingWorldItems) {
        Item item = tracked.item();
        Location itemLocation = tracked.location();
        TextDisplay label = labels.get(tracked.itemId());
        if (visibilityPolicy.cullingEnabled()) {
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

        ItemStack stack = item.getItemStack();
        String matchingName = matchingName(stack);
        NamespacedKey customItemId = blacklist.requiresCustomItemId()
                ? CustomContentManager.customItemId(stack).orElse(null)
                : null;
        int ticksLeft = despawnTicks - item.getTicksLived();
        if (stack.isEmpty() || blacklist.matches(matchingName, stack.getType(), customItemId)
                || item.getPickupDelay() >= Short.MAX_VALUE || ticksLeft <= 0
                || (itemIndex != null && itemIndex.exceedsItemLimit(tracked.worldId(),
                itemLocation.getX(), itemLocation.getY(), itemLocation.getZ(), cramp))) {
            removeLabel(item.getUniqueId());
            return;
        }

        Component text = format(stack, ticksLeft);
        boolean created = false;
        if (label == null || !label.isValid() || !label.getWorld().equals(item.getWorld())) {
            removeLabel(item.getUniqueId());
            label = spawnLabel(item);
            labels.put(item.getUniqueId(), label);
            created = true;
        }
        if (!text.equals(label.text())) {
            label.text(text);
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
                    display.getPersistentDataContainer().set(ownerKey(), PersistentDataType.STRING, "dropped_item_label");
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
        visibilityQueuesPending = false;
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

    private void reconcileLabelVisibility(Collection<Player> viewers, List<TrackedItem> validItems) {
        for (Player player : viewers) {
            UUID playerId = player.getUniqueId();
            VisibilityState state = visibilityStates.computeIfAbsent(playerId,
                    ignored -> new VisibilityState(visibilityPolicy.bucketSize()));
            Set<UUID> desired = state.nextDesired;
            desired.clear();
            Location playerLocation = player.getLocation();
            int trackingDistance = InteractionVisualizer.playerTrackingRange
                    .getOrDefault(player.getWorld(), DEFAULT_TRACKING_DISTANCE);
            double range = visibilityPolicy.cullingEnabled()
                    ? visibilityPolicy.effectiveViewDistance(trackingDistance)
                    : 0.0D;
            double rangeSquared = range * range;

            for (TrackedItem tracked : validItems) {
                TextDisplay label = labels.get(tracked.itemId());
                if (label == null || !label.isValid() || !tracked.item().getWorld().equals(player.getWorld())) {
                    continue;
                }
                if (!visibilityPolicy.cullingEnabled()) {
                    desired.add(tracked.itemId());
                    continue;
                }
                Location location = tracked.location();
                double deltaX = location.getX() - playerLocation.getX();
                double deltaY = location.getY() - playerLocation.getY();
                double deltaZ = location.getZ() - playerLocation.getZ();
                if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= rangeSquared) {
                    desired.add(tracked.itemId());
                }
            }

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
                }
            }
            state.nextDesired = state.desired;
            state.desired = desired;
            for (UUID itemId : desired) {
                if (!state.shown.contains(itemId)) {
                    state.pending.request(itemId);
                    visibilityQueuesPending = true;
                }
            }
        }
    }

    private void drainVisibilityQueues() {
        if (!visibilityQueuesPending) {
            return;
        }
        boolean stillPending = false;
        for (Map.Entry<UUID, VisibilityState> entry : visibilityStates.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            VisibilityState state = entry.getValue();
            if (player == null || !player.isOnline() || !eligibleViewers.contains(entry.getKey())) {
                state.pending.clear();
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
            stillPending |= state.pending.hasPending();
        }
        visibilityQueuesPending = stillPending;
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
        state.pending.clear();
    }

    private Component format(ItemStack stack, int ticksLeft) {
        int amount = stack.getAmount();
        String durability = durability(stack);
        int secondsLeft = Math.max(0, ticksLeft / 20);
        String timerColor = secondsLeft <= 30 ? lowColor : secondsLeft <= 120 ? mediumColor : highColor;
        String timer = timerColor + String.format(java.util.Locale.ROOT, "%02d:%02d", secondsLeft / 60, secondsLeft % 60);

        String template;
        if (ticksLeft >= 600 && durability != null) {
            template = toolsFormatting.replace("{Durability}", durability);
        } else {
            template = amount == 1 ? singularFormatting : regularFormatting;
        }
        String rendered = template.replace("{Amount}", Integer.toString(amount)).replace("{Timer}", timer);
        Component component = LegacyTextComponentCache.parse(rendered);
        return component.replaceText(TextReplacementConfig.builder()
                .matchLiteral("{Item}")
                .replacement(ItemNameUtils.getDisplayName(stack))
                .build());
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
        removeLabel(itemId);
    }

    private void removeLabel(UUID itemId) {
        TextDisplay label = labels.remove(itemId);
        forgetLabelVisibility(itemId, label);
        removeLabel(label);
    }

    private void forgetLabelVisibility(UUID itemId, TextDisplay label) {
        for (Map.Entry<UUID, VisibilityState> entry : visibilityStates.entrySet()) {
            VisibilityState state = entry.getValue();
            state.desired.remove(itemId);
            state.pending.cancel(itemId);
            if (state.shown.remove(itemId) && label != null && label.isValid()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    setLabelVisible(player, label, false);
                }
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

    private static boolean isOwned(Entity entity) {
        return entity.getPersistentDataContainer().has(ownerKey(), PersistentDataType.STRING);
    }

    private static NamespacedKey ownerKey() {
        return new NamespacedKey(InteractionVisualizer.plugin, "visual_entity");
    }

    private record TrackedItem(UUID itemId, Item item, UUID worldId, Location location) {
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
