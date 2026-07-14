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

package com.loohp.interactionvisualizer.objectholders;

import com.loohp.interactionvisualizer.InteractionVisualizer;
import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI;
import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI.Modules;
import com.loohp.interactionvisualizer.blocks.EnchantmentTableDisplay;
import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.utils.ComponentFont;
import com.loohp.interactionvisualizer.utils.LegacyTextComponentCache;
import com.loohp.interactionvisualizer.utils.RomanNumberUtils;
import com.loohp.interactionvisualizer.utils.TranslationUtils;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class EnchantmentTableAnimation {

    public static final EntryKey KEY = new EntryKey("enchantment_table");

    public static final int SET_ITEM = 0;
    public static final int PLAY_ENCHANTMENT = 1;
    public static final int PLAY_PICKUP = 2;
    public static final int CLOSE_TABLE = 3;

    private static final int PICKUP_COMPLETION_DELAY_TICKS = 8;

    private static final Map<Block, EnchantmentTableAnimation> tables = new ConcurrentHashMap<>();

    public static EnchantmentTableAnimation getTableAnimation(Block block, Player player) {
        EnchantmentTableAnimation animation = tables.get(block);
        if (animation == null) {
            animation = new EnchantmentTableAnimation(block, player);
            tables.put(block, animation);
            return animation;
        } else if (animation.getEnchanter().equals(player)) {
            return animation;
        } else {
            return null;
        }
    }
    private final Plugin plugin;
    private final Block block;
    private final Location location;
    private final Player enchanter;
    private final Queue<Supplier<CompletableFuture<Integer>>> taskQueue;
    private final AtomicBoolean enchanting;
    private Optional<Item> item;
    // Inventory transfer can finish while the scheduled enchant visual is still playing.
    private volatile PendingPickup pendingPickup;

    private EnchantmentTableAnimation(Block block, Player enchanter) {
        this.plugin = InteractionVisualizer.plugin;
        this.block = block;
        this.enchanter = enchanter;
        this.location = block.getLocation().clone();
        this.item = Optional.empty();
        this.enchanting = new AtomicBoolean(false);
        this.taskQueue = new ConcurrentLinkedQueue<>();
        tick();
    }

    private void tick() {
        Scheduler.runTaskLater(plugin, this::run, 1);
    }

    private void run() {
        Supplier<CompletableFuture<Integer>> task = taskQueue.poll();
        if (task == null) {
            tick();
            return;
        }

        CompletableFuture<Integer> future;
        try {
            future = task.get();
        } catch (Throwable ignored) {
            tick();
            return;
        }
        if (future == null) {
            tick();
            return;
        }

        future.whenComplete((result, throwable) -> {
            if (throwable != null || result == null || result != CLOSE_TABLE) {
                tick();
            } else {
                tables.remove(block, this);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private CompletableFuture<Integer> playEnchantAnimation(Map<Enchantment, Integer> enchantsToAdd, Integer expCost, ItemStack itemstack) {
        CompletableFuture<Integer> future = new CompletableFuture<>();

        if (item.isPresent() && item.get().isLocked()) {
            future.complete(PLAY_ENCHANTMENT);
            return future;
        }

        this.enchanting.set(true);

        boolean spawned = false;
        if (!this.item.isPresent()) {
            this.item = Optional.of(new Item(location.clone().add(0.5, 1.3, 0.5)));
            spawned = true;
        }

        Item item = this.item.get();

        item.setItemStack(itemstack);
        item.setGravity(false);
        item.setLocked(true);
        item.setVelocity(new Vector(0.0, 0.05, 0.0));
        if (spawned) {
            DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY), item);
        } else {
            DisplayManager.updateItem(item);
        }
        for (Player each : InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY)) {
            each.spawnParticle(Particle.PORTAL, location.clone().add(0.5, 2.6, 0.5), 200);
        }

        Scheduler.runTaskLater(plugin, () -> {
            item.teleport(location.clone().add(0.5, 2.3, 0.5));
            item.setVelocity(new Vector(0, 0, 0));
            DisplayManager.updateItem(item);
        }, 20);

        List<DisplayEntity> stands = new LinkedList<>();

        Scheduler.runTaskLater(plugin, () -> {
            Location standloc = item.getLocation().add(0.0, 0.5, 0.0);
            for (Entry<Enchantment, Integer> entry : enchantsToAdd.entrySet()) {
                Enchantment ench = entry.getKey();
                int level = entry.getValue();
                String customName = EnchantmentTableDisplay.getCustomDefinedEnchantmentNames()
                        .get(EnchantmentTableDisplay.getEnchantmentIdOrKey(ench));
                Component enchantmentName = customName == null || customName.isBlank()
                        ? ench.description()
                        : LegacyTextComponentCache.parse(customName);
                DisplayEntity stand = new DisplayEntity(standloc);
                if (ench.getMaxLevel() != 1 || level != 1) {
                    enchantmentName = enchantmentName.append(ComponentFont.parseFont(Component.text(" " + RomanNumberUtils.toRoman(entry.getValue()), NamedTextColor.AQUA)));
                }
                if (ench.isCursed()) {
                    enchantmentName = enchantmentName.color(NamedTextColor.RED);
                } else {
                    enchantmentName = enchantmentName.color(NamedTextColor.AQUA);
                }
                stand.setCustomName(enchantmentName);
                stand.setCustomNameVisible(true);
                setStand(stand);
                DisplayManager.spawnDisplay(InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY), stand);
                stands.add(stand);
                standloc.add(0.0, 0.3, 0.0);
            }

            DisplayEntity stand = new DisplayEntity(standloc);
            TranslatableComponent levelTrans = Component.translatable(TranslationUtils.getLevel(expCost));
            if (expCost != 1) {
                levelTrans = levelTrans.arguments(Component.text(expCost));
            }
            levelTrans = levelTrans.color(NamedTextColor.GREEN);
            stand.setCustomName(levelTrans);
            stand.setCustomNameVisible(true);
            setStand(stand);
            DisplayManager.spawnDisplay(InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY), stand);
            stands.add(stand);

            DisplayManager.updateItem(item);
        }, 50);

        Scheduler.runTaskLater(plugin, () -> {
            while (!stands.isEmpty()) {
                DisplayEntity stand = stands.remove(0);
                DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand);
            }
            item.setGravity(true);
            DisplayManager.updateItem(item);
        }, 90);

        Scheduler.runTaskLater(plugin, () -> {
            item.teleport(location.clone().add(0.5, 1.3, 0.5));
            item.setGravity(false);
            DisplayManager.updateItem(item);
            item.setLocked(false);

            if (!playPendingPickup(future)) {
                this.enchanting.set(false);
                future.complete(PLAY_ENCHANTMENT);
            }
        }, 98);
        return future;
    }

    private boolean playPendingPickup(CompletableFuture<Integer> enchantFuture) {
        PendingPickup pending = pendingPickup;
        pendingPickup = null;
        if (pending == null) {
            return false;
        }

        try {
            if (!pending.condition().test(this)) {
                return false;
            }
            playPickUpAnimation(pending.itemStack()).whenComplete((ignored, throwable) -> {
                this.enchanting.set(false);
                if (throwable == null) {
                    enchantFuture.complete(PLAY_ENCHANTMENT);
                } else {
                    enchantFuture.completeExceptionally(throwable);
                }
            });
        } catch (Throwable throwable) {
            this.enchanting.set(false);
            enchantFuture.completeExceptionally(throwable);
        }
        return true;
    }

    private CompletableFuture<Integer> playPickUpAnimation(ItemStack itemstack) {
        CompletableFuture<Integer> future = new CompletableFuture<>();

        if (!item.isPresent()) {
            future.complete(PLAY_PICKUP);
            return future;
        }
        Item item = this.item.get();
        item.setItemStack(itemstack);
        item.setLocked(true);
        if (itemstack == null || itemstack.getType().equals(Material.AIR)) {
            future.complete(PLAY_PICKUP);
            return future;
        }

        DisplayManager.updateItem(item);
        DisplayManager.collectItem(item, enchanter);

        Scheduler.runTaskLater(plugin, () -> {
            this.item = Optional.empty();
            future.complete(PLAY_PICKUP);
        }, PICKUP_COMPLETION_DELAY_TICKS);
        return future;
    }

    private CompletableFuture<Integer> close() {
        if (this.item.isPresent()) {
            DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item.get());
        }
        return CompletableFuture.completedFuture(CLOSE_TABLE);
    }

    private CompletableFuture<Integer> setItemStack(ItemStack itemstack) {
        if (itemstack == null || itemstack.getType().equals(Material.AIR)) {
            clearItemStack();
            return CompletableFuture.completedFuture(SET_ITEM);
        }
        if (this.item.isPresent()) {
            this.item.get().setItemStack(itemstack);
            DisplayManager.updateItem(item.get());
        } else {
            this.item = Optional.of(new Item(location.clone().add(0.5, 1.3, 0.5)));
            this.item.get().setItemStack(itemstack);
            DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, KEY), item.get());
            DisplayManager.updateItem(item.get());
        }
        return CompletableFuture.completedFuture(SET_ITEM);
    }

    private void clearItemStack() {
        if (this.item.isPresent()) {
            DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item.get());
            this.item = Optional.empty();
        }
    }

    private void setStand(DisplayEntity stand) {
        stand.useLegacyNameTagStyle();
        stand.setMarker(true);
        stand.setSmall(true);
        stand.setVisible(true);
        stand.setInvulnerable(true);
        stand.setBasePlate(false);
        stand.setVisible(false);
    }

    public ItemStack getItemStack() {
        return item.isPresent() ? item.get().getItemStack() : null;
    }

    public Player getEnchanter() {
        return enchanter;
    }

    public Block getBlock() {
        return block;
    }

    public boolean isEnchanting() {
        return enchanting.get();
    }

    public void queueSetItem(ItemStack itemstack, Predicate<EnchantmentTableAnimation> condition) {
        taskQueue.add(() -> {
            if (condition.test(this)) {
                return setItemStack(itemstack == null ? null : itemstack.clone());
            } else {
                return null;
            }
        });
    }

    public void queueEnchant(Map<Enchantment, Integer> enchantsToAdd, int expCost, ItemStack itemstack, Predicate<EnchantmentTableAnimation> condition) {
        taskQueue.add(() -> {
            if (condition.test(this)) {
                return playEnchantAnimation(enchantsToAdd, expCost, itemstack == null ? null : itemstack.clone());
            } else {
                return null;
            }
        });
    }

    public void queuePickupAnimation(ItemStack itemstack, Predicate<EnchantmentTableAnimation> condition) {
        ItemStack copy = itemstack == null ? null : itemstack.clone();
        if (enchanting.get() && copy != null && !copy.isEmpty()) {
            pendingPickup = new PendingPickup(copy, condition);
            return;
        }
        taskQueue.add(() -> {
            if (condition.test(this)) {
                return playPickUpAnimation(copy);
            } else {
                return null;
            }
        });
    }

    public void queueClose(Predicate<EnchantmentTableAnimation> condition) {
        taskQueue.add(() -> {
            if (condition.test(this)) {
                return close();
            } else {
                return null;
            }
        });
    }

    private record PendingPickup(ItemStack itemStack, Predicate<EnchantmentTableAnimation> condition) {
    }

}
