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

package com.loohp.interactionvisualizer.blocks;

import com.loohp.interactionvisualizer.InteractionVisualizer;
import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI;
import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI.Modules;
import com.loohp.interactionvisualizer.api.VisualizerRunnableDisplay;
import com.loohp.interactionvisualizer.api.events.InteractionVisualizerReloadEvent;
import com.loohp.interactionvisualizer.api.events.TileEntityRemovedEvent;
import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.managers.PlayerLocationManager;
import com.loohp.interactionvisualizer.managers.TileEntityManager;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.objectholders.TileEntity.TileEntityType;
import com.loohp.interactionvisualizer.utils.ComponentFont;
import com.loohp.interactionvisualizer.utils.TranslationUtils;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class JukeBoxDisplay extends VisualizerRunnableDisplay implements Listener {

    public static final EntryKey KEY = new EntryKey("jukebox");
    private static final String ITEM_KEY = "Item";
    private static final String LABEL_KEY = "Label";
    private static final String EMPTY_VISUAL = "N/A";
    private static final double LABEL_Y_OFFSET = 0.55D;

    public ConcurrentHashMap<Block, Map<String, Object>> jukeboxMap = new ConcurrentHashMap<>();
    private int checkingPeriod = 20;
    private int gcPeriod = 600;
    private boolean showDiscName = true;

    public JukeBoxDisplay() {
        onReload(new InteractionVisualizerReloadEvent());
    }

    @EventHandler
    public void onReload(InteractionVisualizerReloadEvent event) {
        checkingPeriod = InteractionVisualizer.plugin.getConfiguration().getInt("Blocks.JukeBox.CheckingPeriod");
        gcPeriod = InteractionVisualizerAPI.getGCPeriod();
        showDiscName = InteractionVisualizer.plugin.getConfiguration().getBoolean("Blocks.JukeBox.Options.ShowDiscName");
    }

    @Override
    public EntryKey key() {
        return KEY;
    }

    @Override
    public ScheduledTask gc() {
        return Scheduler.runTaskTimer(InteractionVisualizer.plugin, () -> {
            Iterator<Entry<Block, Map<String, Object>>> itr = jukeboxMap.entrySet().iterator();
            int count = 0;
            int maxper = (int) Math.ceil((double) jukeboxMap.size() / (double) gcPeriod);
            int delay = 1;
            while (itr.hasNext()) {
                count++;
                if (count > maxper) {
                    count = 0;
                    delay++;
                }
                Entry<Block, Map<String, Object>> entry = itr.next();
                Block block = entry.getKey();
                Map<String, Object> values = entry.getValue();
                Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
                    if (!ownsVisualState(jukeboxMap.get(block), values)) {
                        return;
                    }
                    if (!isActive(block.getLocation())) {
                        if (jukeboxMap.remove(block, values)) {
                            removeVisuals(values);
                        }
                        return;
                    }
                    if (!block.getType().equals(Material.JUKEBOX)) {
                        if (jukeboxMap.remove(block, values)) {
                            removeVisuals(values);
                        }
                        return;
                    }
                }, delay, block.getLocation());
            }
        }, 0, gcPeriod);
    }

    @Override
    public ScheduledTask run() {
        return Scheduler.runTaskTimer(InteractionVisualizer.plugin, () -> {
            Set<Block> list = nearbyJukeBox();
            for (Block block : list) {
                Scheduler.runTask(InteractionVisualizer.plugin, () -> {
                    if (jukeboxMap.get(block) == null && isActive(block.getLocation())) {
                        if (block.getType().equals(Material.JUKEBOX)) {
                            HashMap<String, Object> map = new HashMap<>();
                            map.put(ITEM_KEY, EMPTY_VISUAL);
                            map.put(LABEL_KEY, EMPTY_VISUAL);
                            jukeboxMap.put(block, map);
                        }
                    }
                }, block.getLocation());
            }

            Iterator<Entry<Block, Map<String, Object>>> itr = jukeboxMap.entrySet().iterator();
            int count = 0;
            int maxper = (int) Math.ceil((double) jukeboxMap.size() / (double) checkingPeriod);
            int delay = 1;
            while (itr.hasNext()) {
                Entry<Block, Map<String, Object>> entry = itr.next();

                count++;
                if (count > maxper) {
                    count = 0;
                    delay++;
                }
                Block block = entry.getKey();
                Map<String, Object> values = entry.getValue();
                Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
                    if (!ownsVisualState(jukeboxMap.get(block), values)) {
                        return;
                    }
                    if (!isActive(block.getLocation())) {
                        return;
                    }
                    if (!block.getType().equals(Material.JUKEBOX)) {
                        return;
                    }
                    org.bukkit.block.Jukebox jukebox = (org.bukkit.block.Jukebox) block.getState();

                    {
                        ItemStack itemstack = jukebox.getRecord() == null ? null : (jukebox.getRecord().getType().equals(Material.AIR) ? null : jukebox.getRecord().clone());
                        if (itemstack == null) {
                            removeVisuals(values);
                            return;
                        }

                        Item item;
                        if (values.get(ITEM_KEY) instanceof Item existing) {
                            item = existing;
                            boolean changed = suppressNativeName(item);
                            if (!item.getItemStack().equals(itemstack)) {
                                item.setItemStack(itemstack);
                                changed = true;
                            }
                            if (changed) {
                                DisplayManager.updateItem(item);
                            }
                        } else {
                            item = new Item(jukebox.getLocation().clone().add(0.5, 1.0, 0.5));
                            item.setItemStack(itemstack);
                            item.setVelocity(new Vector(0, 0, 0));
                            item.setPickupDelay(32767);
                            item.setGravity(false);
                            suppressNativeName(item);
                            values.put(ITEM_KEY, item);
                            DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY), item);
                            DisplayManager.updateItem(item);
                        }

                        Component text = showDiscName ? discName(itemstack, jukebox.getPlaying().toString()) : null;
                        reconcileLabel(values, item.getLocation(), text);
                    }
                }, delay, block.getLocation());
            }
        }, 0, checkingPeriod);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakJukeBox(TileEntityRemovedEvent event) {
        Block block = event.getBlock();
        if (!jukeboxMap.containsKey(block)) {
            return;
        }

        Map<String, Object> values = jukeboxMap.remove(block);
        removeVisuals(values);
    }

    private Component discName(ItemStack itemstack, String disc) {
        Component displayName = itemstack.getItemMeta().displayName();
        if (displayName != null) {
            return ComponentFont.parseFont(displayName.colorIfAbsent(getColor(disc)));
        }
        return Component.translatable(TranslationUtils.getRecord(disc)).color(getColor(disc));
    }

    private void reconcileLabel(Map<String, Object> values, Location itemLocation, Component text) {
        Object current = values.get(LABEL_KEY);
        if (text == null) {
            if (current instanceof DisplayEntity label) {
                DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), label);
            }
            values.put(LABEL_KEY, EMPTY_VISUAL);
            return;
        }

        if (current instanceof DisplayEntity label) {
            if (!text.equals(label.getCustomName()) || !label.isCustomNameVisible()) {
                label.setCustomName(text);
                label.setCustomNameVisible(true);
                DisplayManager.updateDisplay(label);
            }
            return;
        }

        DisplayEntity label = new DisplayEntity(labelLocation(itemLocation));
        configureLabel(label);
        label.setCustomName(text);
        values.put(LABEL_KEY, label);
        DisplayManager.spawnDisplay(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY), label);
        DisplayManager.updateDisplay(label);
    }

    private void removeVisuals(Map<String, Object> values) {
        if (values == null) {
            return;
        }
        if (values.get(ITEM_KEY) instanceof Item item) {
            DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
        }
        if (values.get(LABEL_KEY) instanceof DisplayEntity label) {
            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), label);
        }
        values.put(ITEM_KEY, EMPTY_VISUAL);
        values.put(LABEL_KEY, EMPTY_VISUAL);
    }

    static Location labelLocation(Location itemLocation) {
        return itemLocation.clone().add(0.0, LABEL_Y_OFFSET, 0.0);
    }

    static void configureLabel(DisplayEntity label) {
        label.setBillboard(Display.Billboard.CENTER);
        label.setDefaultBackground(true);
        label.setTextScale(1.0F);
        label.setUnboundedTextWidth(true);
        label.setInterpolationDuration(0);
        label.setTeleportDuration(0);
        label.setGravity(false);
        label.setInvulnerable(true);
        label.setSilent(true);
        label.setCustomNameVisible(true);
    }

    static boolean suppressNativeName(Item item) {
        boolean changed = item.getCustomName() != null || item.isCustomNameVisible();
        item.setCustomName((Component) null);
        item.setCustomNameVisible(false);
        return changed;
    }

    static boolean ownsVisualState(Map<String, Object> current, Map<String, Object> scheduled) {
        return current == scheduled;
    }

    public Set<Block> nearbyJukeBox() {
        return TileEntityManager.getTileEntities(TileEntityType.JUKEBOX);
    }

    public boolean isActive(Location loc) {
        return PlayerLocationManager.hasPlayerNearby(loc);
    }

    public NamedTextColor getColor(String material) {
        switch (material) {
            case "MUSIC_DISC_11":
                return NamedTextColor.WHITE;
            case "MUSIC_DISC_13":
                return NamedTextColor.GOLD;
            case "MUSIC_DISC_BLOCKS":
                return NamedTextColor.RED;
            case "MUSIC_DISC_CAT":
                return NamedTextColor.GREEN;
            case "MUSIC_DISC_CHIRP":
                return NamedTextColor.DARK_RED;
            case "MUSIC_DISC_FAR":
                return NamedTextColor.GREEN;
            case "MUSIC_DISC_MALL":
                return NamedTextColor.BLUE;
            case "MUSIC_DISC_MELLOHI":
                return NamedTextColor.LIGHT_PURPLE;
            case "MUSIC_DISC_STAL":
                return NamedTextColor.WHITE;
            case "MUSIC_DISC_STRAD":
                return NamedTextColor.WHITE;
            case "MUSIC_DISC_WAIT":
                return NamedTextColor.AQUA;
            case "MUSIC_DISC_WARD":
                return NamedTextColor.DARK_AQUA;
            case "MUSIC_DISC_PIGSTEP":
                return NamedTextColor.GOLD;
            case "MUSIC_DISC_OTHERSIDE":
                return NamedTextColor.BLUE;
            case "MUSIC_DISC_5":
                return NamedTextColor.DARK_AQUA;
            case "MUSIC_DISC_RELIC":
                return NamedTextColor.AQUA;
            case "MUSIC_DISC_CREATOR":
                return NamedTextColor.GREEN;
            case "MUSIC_DISC_CREATOR_MUSIC_BOX":
                return NamedTextColor.GOLD;
            case "MUSIC_DISC_PRECIPICE":
                return NamedTextColor.GREEN;
            default:
                return NamedTextColor.WHITE;
        }
    }

}
