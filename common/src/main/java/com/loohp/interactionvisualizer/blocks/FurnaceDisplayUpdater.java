/*
 * This file is part of InteractionVisualizer.
 *
 * Copyright (C) 2026. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.loohp.interactionvisualizer.blocks;

import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI;
import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI.Modules;
import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.utils.ChatColorUtils;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.Map;

/** Shared hybrid update path for the three Bukkit furnace variants. */
final class FurnaceDisplayUpdater {

    private FurnaceDisplayUpdater() {
    }

    static boolean update(org.bukkit.block.Furnace furnace, Map<String, Object> values, EntryKey key,
                          String progressBarCharacter, String emptyColor, String filledColor,
                          String noFuelColor, int progressBarLength, String amountPending) {
        Inventory inventory = furnace.getInventory();
        ItemStack itemStack = normalize(inventory.getItem(0));
        if (itemStack == null) {
            itemStack = normalize(inventory.getItem(2));
        }

        if (values.get("Item") instanceof String) {
            if (itemStack != null) {
                Item item = new Item(furnace.getLocation().clone().add(0.5, 1.0, 0.5));
                item.setItemStack(itemStack);
                item.setVelocity(new Vector());
                item.setPickupDelay(32767);
                item.setGravity(false);
                values.put("Item", item);
                DisplayManager.sendItemSpawn(InteractionVisualizerAPI.getPlayerModuleList(Modules.ITEMDROP, key), item);
            } else {
                values.put("Item", "N/A");
            }
        } else {
            Item item = (Item) values.get("Item");
            if (itemStack != null) {
                if (!item.getItemStack().equals(itemStack)) {
                    item.setItemStack(itemStack);
                    DisplayManager.updateItem(item);
                }
            } else {
                values.put("Item", "N/A");
                DisplayManager.removeItem(InteractionVisualizerAPI.getPlayers(), item);
            }
        }

        DisplayEntity stand = (DisplayEntity) values.get("Stand");
        ItemStack input = normalize(inventory.getItem(0));
        if (input == null) {
            hideProgress(stand);
            return false;
        }

        int time = furnace.getCookTime();
        int maximum = furnace.getCookTimeTotal();
        double scaled = scaledProgress(time, maximum, progressBarLength);
        StringBuilder symbol = new StringBuilder(Math.max(16, progressBarLength * 3));
        double i = 1;
        for (; i < scaled; i++) {
            symbol.append(filledColor).append(progressBarCharacter);
        }
        i--;
        if (scaled - i > 0 && scaled - i < 0.67) {
            symbol.append(emptyColor).append(progressBarCharacter);
        } else if (scaled - i > 0) {
            symbol.append(filledColor).append(progressBarCharacter);
        }
        for (i = progressBarLength - 1; i >= scaled; i--) {
            symbol.append(emptyColor).append(progressBarCharacter);
        }

        int left = input.getAmount() - 1;
        if (left > 0) {
            symbol.append(amountPending.replace("{Amount}", Integer.toString(left)));
        }
        String text = symbol.toString();
        if (text.contains("{CompletedAmount}")) {
            ItemStack output = inventory.getItem(2);
            text = text.replace("{CompletedAmount}", Integer.toString(output == null ? 0 : output.getAmount()));
        }
        if (!hasFuel(furnace)) {
            text = noFuelColor + ChatColorUtils.stripColor(text);
        }
        if (!PlainTextComponentSerializer.plainText().serialize(stand.getCustomName()).equals(text)
                || !stand.isCustomNameVisible()) {
            stand.setCustomNameVisible(true);
            stand.setCustomName(text);
            DisplayManager.updateDisplay(stand);
        }
        return furnace.getBurnTime() > 0 || furnace.getCookTime() > 0;
    }

    static double scaledProgress(int time, int maximum, int progressBarLength) {
        int boundedLength = Math.max(0, progressBarLength);
        if (maximum <= 0 || boundedLength == 0) {
            return 0.0D;
        }
        double scaled = (double) time / (double) maximum * (double) boundedLength;
        return Math.max(0.0D, Math.min((double) boundedLength, scaled));
    }

    private static ItemStack normalize(ItemStack itemStack) {
        return itemStack == null || itemStack.getType() == Material.AIR ? null : itemStack;
    }

    private static boolean hasFuel(org.bukkit.block.Furnace furnace) {
        if (furnace.getBurnTime() > 0) {
            return true;
        }
        return normalize(furnace.getInventory().getItem(1)) != null;
    }

    private static void hideProgress(DisplayEntity stand) {
        if (!PlainTextComponentSerializer.plainText().serialize(stand.getCustomName()).isEmpty()
                || stand.isCustomNameVisible()) {
            stand.setCustomNameVisible(false);
            stand.setCustomName("");
            DisplayManager.updateDisplay(stand);
        }
    }

}
