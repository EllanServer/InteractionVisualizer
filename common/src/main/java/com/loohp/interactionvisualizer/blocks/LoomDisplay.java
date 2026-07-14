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
import com.loohp.interactionvisualizer.api.VisualizerInteractDisplay;
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.utils.InventoryUtils;
import com.loohp.interactionvisualizer.utils.LocationUtils;
import com.loohp.interactionvisualizer.utils.VanishUtils;
import com.loohp.interactionvisualizer.scheduler.ScheduledRunnable;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class LoomDisplay extends VisualizerInteractDisplay implements Listener {

    public static final EntryKey KEY = new EntryKey("loom");

    public Map<Block, Map<String, Object>> openedLooms = new HashMap<>();
    private final Map<UUID, Block> loomBlocksByPlayer = new HashMap<>();

    @Override
    public EntryKey key() {
        return KEY;
    }

    @Override
    public ScheduledTask run() {
        return new ScheduledRunnable() {
            public void run() {

                Iterator<Block> itr = openedLooms.keySet().iterator();
                int count = 0;
                int maxper = (int) Math.ceil((double) openedLooms.size() / (double) 5);
                int delay = 1;
                while (itr.hasNext()) {
                    count++;
                    if (count > maxper) {
                        count = 0;
                        delay++;
                    }
                    Block block = itr.next();
                    new ScheduledRunnable() {
                        public void run() {
                            if (!openedLooms.containsKey(block)) {
                                return;
                            }
                            Map<String, Object> map = openedLooms.get(block);
                            if (block.getType().equals(Material.LOOM)) {
                                Player player = (Player) map.get("Player");
                                if (!GameMode.SPECTATOR.equals(player.getGameMode())) {
                                    Block openBlock = resolveLoomBlock(player, player.getOpenInventory());
                                    if (block.equals(openBlock)) {
                                        return;
                                    }
                                }
                            }

                            if (map.get("Banner") instanceof Item item) {
                                DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
                            }
                            openedLooms.remove(block);
                        }
                    }.runTaskLater(InteractionVisualizer.plugin, delay, block.getLocation());
                }
            }
        }.runTaskTimer(InteractionVisualizer.plugin, 0, 5);
    }

    @Override
    public void process(Player player) {
        if (VanishUtils.isVanished(player)) {
            return;
        }
        if (GameMode.SPECTATOR.equals(player.getGameMode())) {
            return;
        }
        InventoryView view = player.getOpenInventory();
        Block block = resolveLoomBlock(player, view);
        if (block == null) {
            return;
        }
        if (!openedLooms.containsKey(block)) {
            Map<String, Object> map = new HashMap<>();
            map.put("Player", player);
            map.putAll(spawnDisplayEntitys(player, block));
            openedLooms.put(block, map);
        }
        Map<String, Object> map = openedLooms.get(block);

        if (!map.get("Player").equals(player)) {
            return;
        }

        ItemStack input = view.getItem(0);
        if (input != null) {
            if (input.getType().equals(Material.AIR)) {
                input = null;
            }
        }
        ItemStack output = view.getItem(3);
        if (output != null) {
            if (output.getType().equals(Material.AIR)) {
                output = null;
            }
        }

        ItemStack item = null;
        if (output == null) {
            if (input != null) {
                item = input;
            }
        } else {
            item = output;
        }

        Item stand = (Item) map.get("Banner");
        if (item != null) {
            if (!item.isSimilar(stand.getItemStack())) {
                stand.setItemStack(item);
                DisplayManager.updateItem(stand);
            }
        } else {
            if (!stand.getItemStack().isEmpty()) {
                stand.setItemStack(ItemStack.empty());
                DisplayManager.updateItem(stand);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLoomInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.LOOM) {
            return;
        }
        loomBlocksByPlayer.put(event.getPlayer().getUniqueId(), event.getClickedBlock());
    }

    private Block resolveLoomBlock(Player player, InventoryView view) {
        if (view == null || view.getTopInventory().getType() != InventoryType.LOOM) {
            return null;
        }
        try {
            Location location = view.getTopInventory().getLocation();
            if (location != null && LocationUtils.isLoaded(location)) {
                Block block = location.getBlock();
                if (block.getType() == Material.LOOM) {
                    loomBlocksByPlayer.put(player.getUniqueId(), block);
                    return block;
                }
            }
        } catch (Exception | AbstractMethodError ignored) {
            // Some server versions do not expose a workstation inventory location.
        }

        UUID playerId = player.getUniqueId();
        Block block = loomBlocksByPlayer.get(playerId);
        if (block == null) {
            return null;
        }
        Location blockLocation = block.getLocation();
        if (!block.getWorld().equals(player.getWorld())
                || blockLocation.clone().add(0.5, 0.5, 0.5).distanceSquared(player.getLocation()) > 64.0
                || !LocationUtils.isLoaded(blockLocation) || block.getType() != Material.LOOM) {
            loomBlocksByPlayer.remove(playerId, block);
            return null;
        }
        return block;
    }

    @EventHandler
    public void onLoomPlayerQuit(PlayerQuitEvent event) {
        loomBlocksByPlayer.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoom(InventoryClickEvent event) {
        if (VanishUtils.isVanished((Player) event.getWhoClicked())) {
            return;
        }
        if (event.isCancelled()) {
            return;
        }
        if (event.getRawSlot() != 0 && event.getRawSlot() != 3) {
            return;
        }
        if (event.getCurrentItem() == null) {
            return;
        }
        if (event.getCurrentItem().getType().equals(Material.AIR)) {
            return;
        }
        if (event.getRawSlot() == 3) {
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

        Player player = (Player) event.getWhoClicked();
        Block block = resolveLoomBlock(player, event.getView());
        if (block == null) {
            return;
        }

        if (!openedLooms.containsKey(block)) {
            return;
        }

        ItemStack itemstack = event.getCurrentItem().clone();
        InventoryView view = player.getOpenInventory();
        Inventory before = Bukkit.createInventory(null, 9);
        before.setItem(0, cloneItem(view.getItem(0)));
        before.setItem(1, cloneItem(view.getItem(1)));
        before.setItem(2, cloneItem(view.getItem(2)));

        Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory() != view) {
                return;
            }

            Inventory after = Bukkit.createInventory(null, 9);
            after.setItem(0, cloneItem(view.getItem(0)));
            after.setItem(1, cloneItem(view.getItem(1)));
            after.setItem(2, cloneItem(view.getItem(2)));

            if (InventoryUtils.compareContents(before, after)) {
                return;
            }

            Map<String, Object> map = openedLooms.get(block);
            if (map == null || !player.equals(map.get("Player"))) {
                return;
            }

            Item item = new Item(block.getLocation().clone().add(0.5, 1.5, 0.5));
            item.setItemStack(itemstack);
            item.setLocked(true);
            DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY), item);
            DisplayManager.updateItem(item);
            DisplayManager.collectItem(item, player);
        }, 1);
    }

    private static ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUseLoom(InventoryClickEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (GameMode.SPECTATOR.equals(event.getWhoClicked().getGameMode())) {
            return;
        }
        if (resolveLoomBlock((Player) event.getWhoClicked(), event.getView()) == null) {
            return;
        }

        if (event.getRawSlot() >= 0 && event.getRawSlot() <= 3) {
            DisplayManager.sendHandMovement(InteractionVisualizerAPI.getPlayers(), (Player) event.getWhoClicked());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragLoom(InventoryDragEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (GameMode.SPECTATOR.equals(event.getWhoClicked().getGameMode())) {
            return;
        }
        if (resolveLoomBlock((Player) event.getWhoClicked(), event.getView()) == null) {
            return;
        }

        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot <= 3) {
                DisplayManager.sendHandMovement(InteractionVisualizerAPI.getPlayers(), (Player) event.getWhoClicked());
                break;
            }
        }
    }

    @EventHandler
    public void onCloseLoom(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Block block = resolveLoomBlock(player, event.getView());
        loomBlocksByPlayer.remove(player.getUniqueId());
        if (block == null) {
            return;
        }

        if (!openedLooms.containsKey(block)) {
            return;
        }

        Map<String, Object> map = openedLooms.get(block);
        if (!map.get("Player").equals(event.getPlayer())) {
            return;
        }

        if (event.getView().getItem(0) != null) {
            if (!event.getView().getItem(0).getType().equals(Material.AIR)) {
                Item item = new Item(block.getLocation().clone().add(0.5, 1.5, 0.5));
                item.setItemStack(event.getView().getItem(0));
                item.setLocked(true);
                DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY), item);
                DisplayManager.updateItem(item);
                DisplayManager.collectItem(item, player);
            }
        }

        if (map.get("Banner") instanceof Item item) {
            DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
        }
        openedLooms.remove(block);
    }

    public Map<String, Item> spawnDisplayEntitys(Player player, Block block) {
        Map<String, Item> map = new HashMap<>();
        Location loc = block.getLocation().clone().add(0.5, 1.0, 0.5);
        Location temploc = new Location(loc.getWorld(), loc.getX(), loc.getY(), loc.getZ()).setDirection(player.getLocation().getDirection().normalize().multiply(-1));
        float yaw = temploc.getYaw();
        Item banner = new Item(loc.clone(), Item.RenderMode.BANNER);
        setStand(banner, yaw);

        map.put("Banner", banner);

        DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMSTAND, KEY), banner);

        return map;
    }

    public void setStand(Item stand, float yaw) {
        stand.setGravity(false);
        stand.setSilent(true);
        stand.setVelocity(new Vector());
        stand.setPickupDelay(32767);
        stand.setRotation(yaw, stand.getLocation().getPitch());
    }

    public Vector rotateVectorAroundY(Vector vector, double degrees) {
        double rad = Math.toRadians(degrees);

        double currentX = vector.getX();
        double currentZ = vector.getZ();

        double cosine = Math.cos(rad);
        double sine = Math.sin(rad);

        return new Vector((cosine * currentX - sine * currentZ), vector.getY(), (sine * currentX + cosine * currentZ));
    }

}
