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

import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/** Shared allocation-light progress update for normal and soul campfires. */
final class CampfireDisplayUpdater {

    private CampfireDisplayUpdater() {
    }

    static boolean update(org.bukkit.block.Campfire campfire, Map<String, Object> values,
                          String progressBarCharacter, String emptyColor, String filledColor,
                          int progressBarLength) {
        boolean lit = campfire.getBlockData() instanceof org.bukkit.block.data.type.Campfire data
                && data.isLit();
        boolean active = false;
        for (int slot = 0; slot < 4; slot++) {
            ItemStack item = campfire.getItem(slot);
            boolean cooking = lit && item != null && item.getType() != Material.AIR;
            active |= cooking;
            DisplayEntity line = (DisplayEntity) values.get(Integer.toString(slot + 1));
            if (!cooking) {
                if (line.updateCustomName("", false)) {
                    DisplayManager.updateDisplay(line);
                }
                continue;
            }
            String progress = progress(campfire.getCookTime(slot), campfire.getCookTimeTotal(slot),
                    progressBarCharacter, emptyColor, filledColor, progressBarLength);
            if (line.updateCustomName(progress, true)) {
                DisplayManager.updateDisplay(line);
            }
        }
        return active;
    }

    static String progress(int time, int maximum, String character,
                           String emptyColor, String filledColor, int length) {
        int boundedLength = Math.max(0, length);
        double scaled = maximum <= 0 ? 0.0D
                : Math.max(0.0D, Math.min((double) boundedLength,
                (double) time / (double) maximum * (double) boundedLength));
        StringBuilder symbol = new StringBuilder(Math.max(16, boundedLength * 3));
        double index = 1.0D;
        for (; index < scaled; index++) {
            symbol.append(filledColor).append(character);
        }
        index--;
        if (scaled - index > 0.0D && scaled - index < 0.67D) {
            symbol.append(emptyColor).append(character);
        } else if (scaled - index > 0.0D) {
            symbol.append(filledColor).append(character);
        }
        for (index = boundedLength - 1.0D; index >= scaled; index--) {
            symbol.append(emptyColor).append(character);
        }
        return symbol.toString();
    }
}
