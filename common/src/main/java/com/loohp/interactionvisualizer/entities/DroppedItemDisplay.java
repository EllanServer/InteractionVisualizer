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
import com.loohp.interactionvisualizer.utils.ComponentFont;
import com.loohp.interactionvisualizer.utils.ItemNameUtils;
import com.loohp.interactionvisualizer.scheduler.ScheduledRunnable;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
    private static final int DEFAULT_VIEW_DISTANCE = 64;
    private static final int VIEW_DISTANCE_HYSTERESIS = 16;
    private static final int DEFAULT_VISIBILITY_BUCKET_SIZE = 128;
    private static final int DEFAULT_VISIBILITY_REFILL = 32;

    private final Map<UUID, Item> trackedItems = new HashMap<>();
    private final Map<UUID, TextDisplay> labels = new HashMap<>();
    private final Set<UUID> eligibleViewers = new HashSet<>();
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
    private int viewDistance = DEFAULT_VIEW_DISTANCE;
    private int visibilityBucketSize = DEFAULT_VISIBILITY_BUCKET_SIZE;
    private int visibilityRefill = DEFAULT_VISIBILITY_REFILL;
    private boolean stripColorBlacklist;
    private DroppedItemBlacklist blacklist = DroppedItemBlacklist.compile(List.of(), DroppedItemDisplay::warn);

    public DroppedItemDisplay() {
        onReload(new InteractionVisualizerReloadEvent());
    }

    @EventHandler
    public void onReload(InteractionVisualizerReloadEvent event) {
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
                .getInt("Entities.Item.Options.ViewDistance");
        viewDistance = configuredViewDistance > 0
                ? Math.max(8, Math.min(512, configuredViewDistance))
                : DEFAULT_VIEW_DISTANCE;
        int configuredBucketSize = InteractionVisualizer.plugin.getConfiguration()
                .getInt("Entities.Item.Options.VisibilityRateLimit.BucketSize");
        visibilityBucketSize = configuredBucketSize > 0
                ? configuredBucketSize
                : DEFAULT_VISIBILITY_BUCKET_SIZE;
        int configuredRefill = InteractionVisualizer.plugin.getConfiguration()
                .getInt("Entities.Item.Options.VisibilityRateLimit.RestorePerTick");
        visibilityRefill = configuredRefill > 0
                ? configuredRefill
                : DEFAULT_VISIBILITY_REFILL;
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
                drainVisibilityQueues();
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
        DroppedItemSpatialIndex.ViewerIndex viewerIndex =
                new DroppedItemSpatialIndex.ViewerIndex(viewers.size());
        for (Player viewer : viewers) {
            Location location = viewer.getLocation();
            viewerIndex.addViewer(viewer.getWorld().getUID(), location.getX(), location.getY(), location.getZ());
        }

        List<TrackedItem> validItems = new ArrayList<>(trackedItems.size());
        Iterator<Map.Entry<UUID, Item>> iterator = trackedItems.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Item> entry = iterator.next();
            Item item = entry.getValue();
            if (!item.isValid() || item.isDead()) {
                iterator.remove();
                removeLabel(entry.getKey());
                continue;
            }
            validItems.add(new TrackedItem(entry.getKey(), item, item.getLocation()));
        }

        if (viewerIndex.isEmpty()) {
            for (UUID itemId : new HashSet<>(labels.keySet())) {
                removeLabel(itemId);
            }
            return;
        }

        DroppedItemSpatialIndex itemIndex = cramp > 0 ? new DroppedItemSpatialIndex() : null;
        if (itemIndex != null) {
            for (TrackedItem tracked : validItems) {
                Location location = tracked.location();
                itemIndex.addItem(tracked.item().getWorld().getUID(),
                        location.getX(), location.getY(), location.getZ());
            }
        }
        for (TrackedItem tracked : validItems) {
            update(tracked, itemIndex, viewerIndex);
        }
        reconcileLabelVisibility(viewers, validItems);
    }

    private void update(TrackedItem tracked, DroppedItemSpatialIndex itemIndex,
                        DroppedItemSpatialIndex.ViewerIndex viewerIndex) {
        Item item = tracked.item();
        Location itemLocation = tracked.location();
        TextDisplay label = labels.get(tracked.itemId());
        int trackingDistance = InteractionVisualizer.playerTrackingRange
                .getOrDefault(item.getWorld(), DEFAULT_VIEW_DISTANCE);
        int effectiveViewDistance = Math.min(viewDistance, trackingDistance);
        int cullingDistance = label == null
                ? effectiveViewDistance
                : effectiveViewDistance + VIEW_DISTANCE_HYSTERESIS;
        if (!viewerIndex.hasViewerWithin(item.getWorld().getUID(),
                itemLocation.getX(), itemLocation.getY(), itemLocation.getZ(), cullingDistance)) {
            removeLabel(tracked.itemId());
            return;
        }

        ItemStack stack = item.getItemStack();
        String matchingName = matchingName(stack);
        NamespacedKey customItemId = blacklist.requiresCustomItemId()
                ? CustomContentManager.customItemId(stack).orElse(null)
                : null;
        int ticksLeft = despawnTicks - item.getTicksLived();
        if (stack.isEmpty() || blacklist.matches(matchingName, stack.getType(), customItemId)
                || item.getPickupDelay() >= Short.MAX_VALUE || ticksLeft <= 0
                || (itemIndex != null && itemIndex.exceedsItemLimit(item.getWorld().getUID(),
                itemLocation.getX(), itemLocation.getY(), itemLocation.getZ(), cramp))) {
            removeLabel(item.getUniqueId());
            return;
        }

        Component text = format(stack, ticksLeft);
        if (label == null || !label.isValid() || !label.getWorld().equals(item.getWorld())) {
            removeLabel(item.getUniqueId());
            label = spawnLabel(item);
            labels.put(item.getUniqueId(), label);
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
        return (float) Math.max(0.125D, Math.min(8.0D, viewDistance / 64.0D));
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
        Map<UUID, Player> desired = new HashMap<>();
        for (Player player : InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY)) {
            if (player.isOnline()) {
                desired.put(player.getUniqueId(), player);
            }
        }
        for (UUID uuid : new HashSet<>(eligibleViewers)) {
            if (!desired.containsKey(uuid)) {
                Player player = Bukkit.getPlayer(uuid);
                removeVisibilityState(uuid, player);
                eligibleViewers.remove(uuid);
            }
        }
        for (Map.Entry<UUID, Player> entry : desired.entrySet()) {
            eligibleViewers.add(entry.getKey());
        }
        return desired.values();
    }

    private void reconcileLabelVisibility(Collection<Player> viewers, List<TrackedItem> validItems) {
        for (Player player : viewers) {
            UUID playerId = player.getUniqueId();
            VisibilityState state = visibilityStates.computeIfAbsent(playerId,
                    ignored -> new VisibilityState(visibilityBucketSize));
            Set<UUID> desired = new HashSet<>();
            Location playerLocation = player.getLocation();
            int trackingDistance = InteractionVisualizer.playerTrackingRange
                    .getOrDefault(player.getWorld(), DEFAULT_VIEW_DISTANCE);
            double range = Math.min(viewDistance, trackingDistance);
            double rangeSquared = range * range;

            for (TrackedItem tracked : validItems) {
                TextDisplay label = labels.get(tracked.itemId());
                if (label == null || !label.isValid() || !tracked.item().getWorld().equals(player.getWorld())) {
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

            for (UUID itemId : new HashSet<>(state.shown)) {
                if (!desired.contains(itemId)) {
                    TextDisplay label = labels.get(itemId);
                    if (label != null && label.isValid()) {
                        PerformanceMetrics.bukkitHide();
                        player.hideEntity(InteractionVisualizer.plugin, label);
                    }
                    state.shown.remove(itemId);
                }
            }
            for (UUID itemId : state.desired) {
                if (!desired.contains(itemId)) {
                    state.pending.cancel(itemId);
                }
            }
            state.desired = desired;
            for (UUID itemId : desired) {
                if (!state.shown.contains(itemId)) {
                    state.pending.request(itemId);
                }
            }
        }
    }

    private void drainVisibilityQueues() {
        for (Map.Entry<UUID, VisibilityState> entry : visibilityStates.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            VisibilityState state = entry.getValue();
            if (player == null || !player.isOnline() || !eligibleViewers.contains(entry.getKey())) {
                continue;
            }
            for (UUID itemId : state.pending.drain(visibilityBucketSize, visibilityRefill, id -> {
                TextDisplay label = labels.get(id);
                return state.desired.contains(id) && !state.shown.contains(id)
                        && label != null && label.isValid();
            })) {
                TextDisplay label = labels.get(itemId);
                if (label != null && label.isValid()) {
                    PerformanceMetrics.bukkitShow();
                    player.showEntity(InteractionVisualizer.plugin, label);
                    state.shown.add(itemId);
                }
            }
        }
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
                    PerformanceMetrics.bukkitHide();
                    player.hideEntity(InteractionVisualizer.plugin, label);
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
        Component component = ComponentFont.parseFont(LegacyComponentSerializer.legacySection().deserialize(rendered));
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
                    PerformanceMetrics.bukkitHide();
                    player.hideEntity(InteractionVisualizer.plugin, label);
                }
            }
        }
    }

    private void removeLabel(TextDisplay label) {
        if (label != null && label.isValid()) {
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

    private record TrackedItem(UUID itemId, Item item, Location location) {
    }

    private static final class VisibilityState {

        private Set<UUID> desired = new HashSet<>();
        private final Set<UUID> shown = new HashSet<>();
        private final VisibilityTokenBucket<UUID> pending;

        private VisibilityState(int initialTokens) {
            this.pending = new VisibilityTokenBucket<>(initialTokens);
        }
    }
}
