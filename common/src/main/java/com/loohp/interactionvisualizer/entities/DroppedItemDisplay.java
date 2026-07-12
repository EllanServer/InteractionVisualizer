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

import java.util.HashMap;
import java.util.HashSet;
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

    private final Map<UUID, Item> trackedItems = new HashMap<>();
    private final Map<UUID, TextDisplay> labels = new HashMap<>();
    private final Set<UUID> eligibleViewers = new HashSet<>();

    private String regularFormatting;
    private String singularFormatting;
    private String toolsFormatting;
    private String highColor = "";
    private String mediumColor = "";
    private String lowColor = "";
    private int cramp = 6;
    private int updateRate = 20;
    private int ticksUntilUpdate;
    private int despawnTicks = 6000;
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
        updateRate = Math.max(1, InteractionVisualizer.plugin.getConfiguration().getInt("Entities.Item.Options.UpdateRate"));
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
                Scheduler.runTask(InteractionVisualizer.plugin, () -> removeLabel(label));
            }
        }
    }

    private void track(Item item) {
        trackedItems.put(item.getUniqueId(), item);
    }

    private void tickAll() {
        reconcileEligibleViewers();
        for (Map.Entry<UUID, Item> entry : new HashMap<>(trackedItems).entrySet()) {
            Item item = entry.getValue();
            if (!item.isValid() || item.isDead()) {
                remove(entry.getKey());
                continue;
            }
            update(item);
        }
    }

    private void update(Item item) {
        ItemStack stack = item.getItemStack();
        String matchingName = matchingName(stack);
        NamespacedKey customItemId = blacklist.requiresCustomItemId()
                ? CustomContentManager.customItemId(stack).orElse(null)
                : null;
        int ticksLeft = despawnTicks - item.getTicksLived();
        if (stack.isEmpty() || blacklist.matches(matchingName, stack.getType(), customItemId)
                || item.getPickupDelay() >= Short.MAX_VALUE || ticksLeft <= 0 || isCramping(item)) {
            removeLabel(item.getUniqueId());
            return;
        }

        Component text = format(stack, ticksLeft);
        TextDisplay label = labels.get(item.getUniqueId());
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
        boolean mounted = item.equals(label.getVehicle()) || item.addPassenger(label);
        if (created) {
            // Mount before revealing the label so Paper can pair both entities
            // with their passenger relationship in the initial tracking bundle.
            showToEligibleViewers(label);
        }
        if (mounted) {
            // A mounted display follows the item on every client render frame.
            // Text refreshes stay low-frequency without sampling item positions.
            if (label.getInterpolationDuration() != 0) {
                label.setInterpolationDuration(0);
            }
            if (label.getTeleportDuration() != 0) {
                label.setTeleportDuration(0);
            }
        } else {
            // Preserve a safe fallback if another plugin cancels the mount or
            // changes either entity during the update.
            int transitionTicks = Math.min(59, updateRate);
            if (label.getTeleportDuration() != transitionTicks) {
                label.setTeleportDuration(transitionTicks);
            }
            label.teleport(item.getLocation().add(0.0, item.getHeight() * 1.7, 0.0));
        }
    }

    private TextDisplay spawnLabel(Item item) {
        return item.getWorld().spawn(item.getLocation().add(0.0, item.getHeight() * 1.7, 0.0),
                TextDisplay.class, display -> {
                    display.setPersistent(false);
                    display.setVisibleByDefault(false);
                    display.setGravity(false);
                    display.setInvulnerable(true);
                    display.setSilent(true);
                    display.setNoPhysics(true);
                    display.setBillboard(Display.Billboard.CENTER);
                    display.setViewRange(1.0F);
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

    private void reconcileEligibleViewers() {
        Map<UUID, Player> desired = new HashMap<>();
        for (Player player : InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY)) {
            if (player.isOnline()) {
                desired.put(player.getUniqueId(), player);
            }
        }
        for (UUID uuid : new HashSet<>(eligibleViewers)) {
            if (!desired.containsKey(uuid)) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    for (TextDisplay label : labels.values()) {
                        if (label.isValid()) {
                            player.hideEntity(InteractionVisualizer.plugin, label);
                        }
                    }
                }
                eligibleViewers.remove(uuid);
            }
        }
        for (Map.Entry<UUID, Player> entry : desired.entrySet()) {
            if (eligibleViewers.add(entry.getKey())) {
                Player player = entry.getValue();
                for (TextDisplay label : labels.values()) {
                    if (label.isValid()) {
                        player.showEntity(InteractionVisualizer.plugin, label);
                    }
                }
            }
        }
    }

    private void showToEligibleViewers(TextDisplay label) {
        for (UUID uuid : eligibleViewers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.showEntity(InteractionVisualizer.plugin, label);
            }
        }
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

    private boolean isCramping(Item item) {
        return cramp > 0 && item.getWorld()
                .getNearbyEntitiesByType(Item.class, item.getLocation(), 0.5, 0.5, 0.5)
                .stream()
                .filter(nearby -> !isOwned(nearby))
                .limit(cramp + 1L)
                .count() > cramp;
    }

    private void remove(UUID itemId) {
        trackedItems.remove(itemId);
        removeLabel(itemId);
    }

    private void removeLabel(UUID itemId) {
        removeLabel(labels.remove(itemId));
    }

    private void removeLabel(TextDisplay label) {
        if (label != null && label.isValid()) {
            for (UUID uuid : eligibleViewers) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.hideEntity(InteractionVisualizer.plugin, label);
                }
            }
            label.remove();
        }
    }

    private static boolean isOwned(Entity entity) {
        return entity.getPersistentDataContainer().has(ownerKey(), PersistentDataType.STRING);
    }

    private static NamespacedKey ownerKey() {
        return new NamespacedKey(InteractionVisualizer.plugin, "visual_entity");
    }
}
