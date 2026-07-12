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

package com.loohp.interactionvisualizer.utils;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public class InventoryUtils {

    public static boolean compareContents(Inventory first, Inventory second) {
        int size = Math.max(first.getSize(), second.getSize());
        for (int i = 0; i < size; i++) {
            ItemStack firstItem = i < first.getSize() ? first.getItem(i) : null;
            ItemStack secondItem = i < second.getSize() ? second.getItem(i) : null;
            if (firstItem != null && firstItem.getType().equals(Material.AIR)) {
                firstItem = null;
            }
            if (secondItem != null && secondItem.getType().equals(Material.AIR)) {
                secondItem = null;
            }
            if (firstItem == null && secondItem != null) {
                return false;
            } else if (secondItem == null && firstItem != null) {
                return false;
            } else if (firstItem != null && secondItem != null && (!firstItem.isSimilar(secondItem) || firstItem.getAmount() != secondItem.getAmount())) {
                return false;
            }
        }
        return true;
    }

    public static boolean stillHaveSpace(Inventory inv, Material material) {
        int size = inv.getSize();
        if (inv instanceof PlayerInventory) {
            size = 36;
        }
        for (int i = 0; i < size; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null) {
                return true;
            }
            if (item.getType().equals(Material.AIR)) {
                return true;
            }
            if (item.getType().equals(material)) {
                if (item.getAmount() < item.getType().getMaxStackSize()) {
                    return true;
                }
            }
        }
        return false;
    }

}
