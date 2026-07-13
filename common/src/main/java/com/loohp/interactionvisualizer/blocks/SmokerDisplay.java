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
import com.loohp.interactionvisualizer.api.events.TileEntityActivatedEvent;
import com.loohp.interactionvisualizer.api.events.TileEntityAddedEvent;
import com.loohp.interactionvisualizer.api.events.TileEntityDeactivatedEvent;
import com.loohp.interactionvisualizer.api.events.TileEntityRemovedEvent;
import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.managers.PlayerLocationManager;
import com.loohp.interactionvisualizer.managers.PerformanceMetrics;
import com.loohp.interactionvisualizer.managers.TileEntityManager;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.objectholders.TileEntity.TileEntityType;
import com.loohp.interactionvisualizer.utils.ChatColorUtils;
import com.loohp.interactionvisualizer.utils.InventoryUtils;
import com.loohp.interactionvisualizer.utils.VanishUtils;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SmokerDisplay extends VisualizerRunnableDisplay implements Listener {

    public static final EntryKey KEY = new EntryKey("smoker");

    public ConcurrentHashMap<Block, Map<String, Object>> smokerMap = new ConcurrentHashMap<>();
    private int checkingPeriod = 20;
    private int gcPeriod = 600;
    private String progressBarCharacter = "";
    private String emptyColor = "&7";
    private String filledColor = "&e";
    private String noFuelColor = "&c";
    private int progressBarLength = 10;
    private String amountPending = " &7+{Amount}";
    private final BlockUpdateScheduler<Block> blockUpdates;

    public SmokerDisplay() {
        onReload(new InteractionVisualizerReloadEvent());
        this.blockUpdates = new BlockUpdateScheduler<>(this::nearbySmoker,
                this.checkingPeriod, this.gcPeriod);
    }

    @EventHandler
    public void onReload(InteractionVisualizerReloadEvent event) {
        checkingPeriod = InteractionVisualizer.plugin.getConfiguration().getInt("Blocks.Smoker.CheckingPeriod");
        gcPeriod = InteractionVisualizerAPI.getGCPeriod();
        progressBarCharacter = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.Smoker.Options.ProgressBarCharacter"));
        emptyColor = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.Smoker.Options.EmptyColor"));
        filledColor = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.Smoker.Options.FilledColor"));
        noFuelColor = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.Smoker.Options.NoFuelColor"));
        progressBarLength = InteractionVisualizer.plugin.getConfiguration().getInt("Blocks.Smoker.Options.ProgressBarLength");
        amountPending = ChatColorUtils.translateAlternateColorCodes('&', InteractionVisualizer.plugin.getConfiguration().getString("Blocks.Smoker.Options.AmountPending"));
    }

    @Override
    public EntryKey key() {
        return KEY;
    }

    @Override
    public ScheduledTask gc() {
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            return null;
        }
        return Scheduler.runTaskTimer(InteractionVisualizer.plugin, () -> {
            Iterator<Entry<Block, Map<String, Object>>> itr = smokerMap.entrySet().iterator();
            int count = 0;
            int maxper = (int) Math.ceil((double) smokerMap.size() / (double) gcPeriod);
            int delay = 1;
            while (itr.hasNext()) {
                count++;
                if (count > maxper) {
                    count = 0;
                    delay++;
                }
                Entry<Block, Map<String, Object>> entry = itr.next();
                Block block = entry.getKey();
                Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
                   if (!isActive(block.getLocation())) {
                        Map<String, Object> map = entry.getValue();
                        if (map.get("Item") instanceof Item) {
                            Item item = (Item) map.get("Item");
                            DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
                        }
                        if (map.get("Stand") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("Stand");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        smokerMap.remove(block);
                        return;
                    }
                    if (!block.getType().equals(Material.SMOKER)) {
                        Map<String, Object> map = entry.getValue();
                        if (map.get("Item") instanceof Item) {
                            Item item = (Item) map.get("Item");
                            DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
                        }
                        if (map.get("Stand") instanceof DisplayEntity) {
                            DisplayEntity stand = (DisplayEntity) map.get("Stand");
                            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
                        }
                        smokerMap.remove(block);
                        return;
                    }
                }, delay, block.getLocation());
            }
        }, 0, gcPeriod);
    }

    @Override
    public ScheduledTask run() {
        if (!InteractionVisualizer.eventDrivenBlockUpdates) {
            return legacyRun();
        }
        return Scheduler.runTaskTimer(InteractionVisualizer.plugin, () -> {
            boolean collecting = PerformanceMetrics.isCollecting();
            long start = collecting ? System.nanoTime() : 0L;
            int checks = this.blockUpdates.tick(Bukkit.getCurrentTick(),
                    InteractionVisualizer.blockUpdateMaxDirtyPerTick, this::updateHybridBlock);
            if (collecting) {
                PerformanceMetrics.blockUpdateChecks(checks, System.nanoTime() - start);
            }
        }, 0, 1);
    }

    private ScheduledTask legacyRun() {
        return Scheduler.runTaskTimer(InteractionVisualizer.plugin, () -> {
            Set<Block> list = nearbySmoker();
            for (Block block : list) {
                Scheduler.runTask(InteractionVisualizer.plugin, () -> {
                    if (smokerMap.get(block) == null && isActive(block.getLocation())) {
                        if (block.getType().equals(Material.SMOKER)) {
                            Map<String, Object> map = new HashMap<>();
                            map.put("Item", "N/A");
                            map.putAll(spawnDisplayEntitys(block));
                            smokerMap.put(block, map);
                        }
                    }
                }, block.getLocation());
            }

            Iterator<Entry<Block, Map<String, Object>>> itr = smokerMap.entrySet().iterator();
            int count = 0;
            int maxper = (int) Math.ceil((double) smokerMap.size() / (double) checkingPeriod);
            int delay = 1;
            while (itr.hasNext()) {
                Entry<Block, Map<String, Object>> entry = itr.next();

                count++;
                if (count > maxper) {
                    count = 0;
                    delay++;
                }
                Block block = entry.getKey();
                Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
                    boolean collecting = PerformanceMetrics.isCollecting();
                    long start = collecting ? System.nanoTime() : 0L;
                    try {
                        if (!isActive(block.getLocation())) {
                            return;
                        }
                        if (!block.getType().equals(Material.SMOKER)) {
                            return;
                        }
                        org.bukkit.block.Smoker smoker = (org.bukkit.block.Smoker) block.getState();

                        {
                        Inventory inv = smoker.getInventory();
                        ItemStack itemstack = inv.getItem(0);
                        if (itemstack != null) {
                            if (itemstack.getType().equals(Material.AIR)) {
                                itemstack = null;
                            }
                        }

                        if (itemstack == null) {
                            itemstack = inv.getItem(2);
                            if (itemstack != null) {
                                if (itemstack.getType().equals(Material.AIR)) {
                                    itemstack = null;
                                }
                            }
                        }

                        Item item = null;
                        if (entry.getValue().get("Item") instanceof String) {
                            if (itemstack != null) {
                                item = new Item(smoker.getLocation().clone().add(0.5, 1.0, 0.5));
                                item.setItemStack(itemstack);
                                item.setVelocity(new Vector(0, 0, 0));
                                item.setPickupDelay(32767);
                                item.setGravity(false);
                                entry.getValue().put("Item", item);
                                DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY), item);
                                DisplayManager.updateItem(item);
                            } else {
                                entry.getValue().put("Item", "N/A");
                            }
                        } else {
                            item = (Item) entry.getValue().get("Item");
                            if (itemstack != null) {
                                if (!item.getItemStack().equals(itemstack)) {
                                    item.setItemStack(itemstack);
                                    DisplayManager.updateItem(item);
                                }
                            } else {
                                entry.getValue().put("Item", "N/A");
                                DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
                            }
                        }

                        DisplayEntity stand = (DisplayEntity) entry.getValue().get("Stand");
                        if (hasItemToCook(smoker)) {
                            int time = smoker.getCookTime();
                            int max = smoker.getCookTimeTotal();
                            String symbol = "";
                            double percentagescaled = (double) time / (double) max * (double) progressBarLength;
                            double i = 1;
                            for (i = 1; i < percentagescaled; i++) {
                                symbol = symbol + filledColor + progressBarCharacter;
                            }
                            i = i - 1;
                            if ((percentagescaled - i) > 0 && (percentagescaled - i) < 0.33) {
                                symbol += emptyColor + progressBarCharacter;
                            } else if ((percentagescaled - i) > 0 && (percentagescaled - i) < 0.67) {
                                symbol += emptyColor + progressBarCharacter;
                            } else if ((percentagescaled - i) > 0) {
                                symbol += filledColor + progressBarCharacter;
                            }
                            for (i = progressBarLength - 1; i >= percentagescaled; i--) {
                                symbol += emptyColor + progressBarCharacter;
                            }

                            int left = inv.getItem(0).getAmount() - 1;
                            if (left > 0) {
                                symbol += amountPending.replace("{Amount}", left + "");
                            }
                            if (symbol.contains("{CompletedAmount}")) {
                                symbol = symbol.replace("{CompletedAmount}", (inv.getItem(2) == null ? 0 : inv.getItem(2).getAmount()) + "");
                            }
                            if (hasFuel(smoker)) {
                                if (!PlainTextComponentSerializer.plainText().serialize(stand.getCustomName()).equals(symbol) || !stand.isCustomNameVisible()) {
                                    stand.setCustomNameVisible(true);
                                    stand.setCustomName(symbol);
                                    DisplayManager.updateDisplay(stand);
                                }
                            } else {
                                symbol = noFuelColor + ChatColorUtils.stripColor(symbol);
                                if (!PlainTextComponentSerializer.plainText().serialize(stand.getCustomName()).equals(symbol) || !stand.isCustomNameVisible()) {
                                    stand.setCustomNameVisible(true);
                                    stand.setCustomName(symbol);
                                    DisplayManager.updateDisplay(stand);
                                }
                            }
                        } else {
                            if (!PlainTextComponentSerializer.plainText().serialize(stand.getCustomName()).equals("") || stand.isCustomNameVisible()) {
                                stand.setCustomNameVisible(false);
                                stand.setCustomName("");
                                DisplayManager.updateDisplay(stand);
                            }
                        }
                        }
                    } finally {
                        if (collecting) {
                            PerformanceMetrics.blockUpdateChecks(1, System.nanoTime() - start);
                        }
                    }
                }, delay, block.getLocation());
            }
        }, 0, checkingPeriod);
    }

    private boolean updateHybridBlock(Block block) {
        if (!TileEntityManager.getTileEntities(TileEntityType.SMOKER).contains(block)) {
            removeTrackedDisplay(block);
            return false;
        }
        if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            removeTrackedDisplay(block);
            return false;
        }
        if (!isSmoker(block.getType())) {
            removeTrackedDisplay(block);
            return false;
        }
        if (!isActive(block.getLocation())) {
            return false;
        }
        Map<String, Object> values = smokerMap.get(block);
        if (values == null) {
            values = new HashMap<>();
            values.put("Item", "N/A");
            values.putAll(spawnDisplayEntitys(block));
            smokerMap.put(block, values);
        }
        org.bukkit.block.Furnace furnace = (org.bukkit.block.Furnace) block.getState();
        return FurnaceDisplayUpdater.update(furnace, values, KEY, progressBarCharacter, emptyColor,
                filledColor, noFuelColor, progressBarLength, amountPending);
    }

    private void markDirty(Block block) {
        if (InteractionVisualizer.eventDrivenBlockUpdates && block != null && isSmoker(block.getType())) {
            blockUpdates.markDirty(block, (long) Bukkit.getCurrentTick() + 1L);
        }
    }

    /** Optimized aggregate-listener entry after the inventory location has been resolved once. */
    public void onSmokerInventoryChanged(Block block) {
        markDirty(block);
    }

    private void markDirty(Inventory inventory) {
        if (!InteractionVisualizer.eventDrivenBlockUpdates) {
            return;
        }
        Location location;
        try {
            location = inventory.getLocation();
        } catch (Exception | AbstractMethodError ignored) {
            return;
        }
        if (location != null) {
            markDirty(location.getBlock());
        }
    }

    public void onSmokerBurn(FurnaceBurnEvent event) {
        markDirty(event.getBlock());
    }

    public void onSmokerStartSmelt(FurnaceStartSmeltEvent event) {
        markDirty(event.getBlock());
    }

    public void onSmokerSmelt(FurnaceSmeltEvent event) {
        markDirty(event.getBlock());
    }

    public void onSmokerExtract(FurnaceExtractEvent event) {
        markDirty(event.getBlock());
    }

    public void onSmokerMoveItem(InventoryMoveItemEvent event) {
        markDirty(event.getSource());
        markDirty(event.getDestination());
    }

    public void onSmokerAdded(TileEntityAddedEvent event) {
        if (event.getTileEntityType() == TileEntityType.SMOKER) {
            markDirty(event.getBlock());
        }
    }

    public void onSmokerActivated(TileEntityActivatedEvent event) {
        if (event.getTileEntityType() == TileEntityType.SMOKER) {
            markDirty(event.getBlock());
        }
    }

    public void onSmokerDeactivated(TileEntityDeactivatedEvent event) {
        if (InteractionVisualizer.eventDrivenBlockUpdates
                && event.getTileEntityType() == TileEntityType.SMOKER) {
            removeTrackedDisplay(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSmoker(InventoryClickEvent event) {
        if (VanishUtils.isVanished((Player) event.getWhoClicked())) {
            return;
        }
        if (event.isCancelled()) {
            return;
        }
        if (event.getRawSlot() != 0 && event.getRawSlot() != 2) {
            return;
        }
        if (event.getCurrentItem() == null) {
            return;
        }
        if (event.getCurrentItem().getType().equals(Material.AIR)) {
            return;
        }
        if (event.getRawSlot() == 2) {
            if (event.getCursor() != null) {
                if (!event.getCursor().getType().equals(Material.AIR)) {
                    if (event.getCursor().getAmount() >= event.getCursor().getType().getMaxStackSize()) {
                        return;
                    }
                }
            }
        } else {
            if (event.getCursor() != null) {
                if (event.getCursor().getType().equals(event.getCurrentItem().getType())) {
                    return;
                }
            }
        }

        if (event.isShiftClick()) {
            if (!InventoryUtils.stillHaveSpace(event.getWhoClicked().getInventory(), event.getView().getItem(event.getRawSlot()).getType())) {
                return;
            }
        }
        int hotbarSlot = event.getHotbarButton();
        if (hotbarSlot >= 0 && event.getAction().equals(InventoryAction.HOTBAR_SWAP)) {
            if (event.getWhoClicked().getInventory().getItem(hotbarSlot) != null && !event.getWhoClicked().getInventory().getItem(hotbarSlot).getType().equals(Material.AIR)) {
                return;
            }
        }

        if (event.getView().getTopInventory() == null) {
            return;
        }
        try {
            if (event.getView().getTopInventory().getLocation() == null) {
                return;
            }
        } catch (Exception | AbstractMethodError e) {
            return;
        }
        if (event.getView().getTopInventory().getLocation().getBlock() == null) {
            return;
        }
        if (!event.getView().getTopInventory().getLocation().getBlock().getType().equals(Material.SMOKER)) {
            return;
        }
        markDirty(event.getView().getTopInventory().getLocation().getBlock());

        Block block = event.getView().getTopInventory().getLocation().getBlock();

        if (!smokerMap.containsKey(block)) {
            return;
        }

        Map<String, Object> map = smokerMap.get(block);

        int slot = event.getRawSlot();
        ItemStack itemstack = event.getCurrentItem().clone();
        Player player = (Player) event.getWhoClicked();

        Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {

            if (player.getOpenInventory().getItem(slot) == null || (itemstack.isSimilar(player.getOpenInventory().getItem(slot)) && itemstack.getAmount() == player.getOpenInventory().getItem(slot).getAmount())) {
                return;
            }

            if (map.get("Item") instanceof String) {
                map.put("Item", new Item(block.getLocation().clone().add(0.5, 1.2, 0.5)));
            }
            Item item = (Item) map.get("Item");
            map.put("Item", "N/A");

            item.setItemStack(itemstack);
            item.setLocked(true);

            DisplayManager.updateItem(item);
            DisplayManager.collectItem(item, player);
        }, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUseSmoker(InventoryClickEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (GameMode.SPECTATOR.equals(event.getWhoClicked().getGameMode())) {
            return;
        }
        if (event.getView().getTopInventory() == null) {
            return;
        }
        try {
            if (event.getView().getTopInventory().getLocation() == null) {
                return;
            }
        } catch (Exception | AbstractMethodError e) {
            return;
        }
        if (event.getView().getTopInventory().getLocation().getBlock() == null) {
            return;
        }
        if (!event.getView().getTopInventory().getLocation().getBlock().getType().equals(Material.SMOKER)) {
            return;
        }

        if (event.getRawSlot() >= 0 && event.getRawSlot() <= 2) {
            DisplayManager.sendHandMovement(InteractionVisualizerAPI.getPlayers(), (Player) event.getWhoClicked());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragSmoker(InventoryDragEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (GameMode.SPECTATOR.equals(event.getWhoClicked().getGameMode())) {
            return;
        }
        if (event.getView().getTopInventory() == null) {
            return;
        }
        try {
            if (event.getView().getTopInventory().getLocation() == null) {
                return;
            }
        } catch (Exception | AbstractMethodError e) {
            return;
        }
        if (event.getView().getTopInventory().getLocation().getBlock() == null) {
            return;
        }
        if (!event.getView().getTopInventory().getLocation().getBlock().getType().equals(Material.SMOKER)) {
            return;
        }

        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot <= 2) {
                markDirty(event.getView().getTopInventory().getLocation().getBlock());
                DisplayManager.sendHandMovement(InteractionVisualizerAPI.getPlayers(), (Player) event.getWhoClicked());
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakSmoker(BlockBreakEvent event) {
        removeTrackedDisplay(event.getBlock());
    }

    public void onRemoveSmoker(TileEntityRemovedEvent event) {
        if (InteractionVisualizer.eventDrivenBlockUpdates
                && event.getTileEntityType() == TileEntityType.SMOKER) {
            removeTrackedDisplay(event.getBlock());
        }
    }

    private void removeTrackedDisplay(Block block) {
        blockUpdates.remove(block);
        Map<String, Object> map = smokerMap.remove(block);
        if (map == null) {
            return;
        }
        if (map.get("Item") instanceof Item) {
            Item item = (Item) map.get("Item");
            DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
        }
        if (map.get("Stand") instanceof DisplayEntity) {
            DisplayEntity stand = (DisplayEntity) map.get("Stand");
            DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
        }
    }

    public boolean hasItemToCook(org.bukkit.block.Smoker smoker) {
        Inventory inv = smoker.getInventory();
        if (inv.getItem(0) != null) {
            return !inv.getItem(0).getType().equals(Material.AIR);
        }
        return false;
    }

    public boolean hasFuel(org.bukkit.block.Smoker smoker) {
        if (smoker.getBurnTime() > 0) {
            return true;
        }
        Inventory inv = smoker.getInventory();
        if (inv.getItem(1) != null) {
            return !inv.getItem(1).getType().equals(Material.AIR);
        }
        return false;
    }

    public Set<Block> nearbySmoker() {
        return TileEntityManager.getTileEntities(TileEntityType.SMOKER);
    }

    public boolean isActive(Location loc) {
        return PlayerLocationManager.hasPlayerNearby(loc);
    }

    private boolean isSmoker(Material material) {
        return material == Material.SMOKER;
    }

    public Map<String, DisplayEntity> spawnDisplayEntitys(Block block) {
        Map<String, DisplayEntity> map = new HashMap<>();
        Location origin = block.getLocation();

        BlockData blockData = block.getState().getBlockData();
        BlockFace facing = ((Directional) blockData).getFacing();
        Location target = block.getRelative(facing).getLocation();
        Vector direction = target.toVector().subtract(origin.toVector()).multiply(0.7);

        Location loc = block.getLocation().clone().add(direction).add(0.5, 0.2, 0.5);
        loc.setDirection(facing.getDirection());
        DisplayEntity slot1 = new DisplayEntity(loc.clone());
        setStand(slot1);

        map.put("Stand", slot1);

        DisplayManager.spawnDisplay(InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY), slot1);

        return map;
    }

    public void setStand(DisplayEntity stand) {
        stand.setBasePlate(false);
        stand.setMarker(true);
        stand.setGravity(false);
        stand.setSmall(true);
        stand.setSilent(true);
        stand.setInvulnerable(true);
        stand.setVisible(false);
        stand.setCustomName("");
        stand.setRightArmPose(EulerAngle.ZERO);
    }

}
