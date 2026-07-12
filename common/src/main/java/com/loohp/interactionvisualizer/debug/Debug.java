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

package com.loohp.interactionvisualizer.debug;

import com.loohp.interactionvisualizer.InteractionVisualizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class Debug implements Listener {

    private ItemStack bone;

    public Debug() {
        Bukkit.removeRecipe(new NamespacedKey(InteractionVisualizer.plugin, "nana_bone"));

        if (InteractionVisualizer.plugin.getConfiguration().contains("Special.b")) {
            if (!InteractionVisualizer.plugin.getConfiguration().getBoolean("Special.b")) {
                return;
            }
        }

        bone = new ItemStack(Material.BONE, 1);
        ItemMeta meta = bone.getItemMeta();
        meta.displayName(Component.text("Nana's Bone", NamedTextColor.YELLOW));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Lost ", NamedTextColor.GRAY)
                .append(Component.text("In-", NamedTextColor.GOLD))
                .append(Component.text("Maginary~~", NamedTextColor.LIGHT_PURPLE)));
        lore.add(Component.empty());
        lore.add(Component.text("https://www.instagram.com/narliar/", NamedTextColor.GOLD));
        lore.add(Component.empty());
        lore.add(Component.text("EasterEgg tribute to the IV author's ", NamedTextColor.GRAY)
                .append(Component.text("Adorable", NamedTextColor.RED)));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        bone.setItemMeta(meta);
        bone.addUnsafeEnchantment(Enchantment.POWER, 1);
    }

    @EventHandler
    public void onJoinPluginActive(PlayerJoinEvent event) {
        if (event.getPlayer().getName().equals("LOOHP") || event.getPlayer().getName().equals("AppLEshakE")) {
            event.getPlayer().sendMessage(Component.text(
                    "InteractionVisualizer " + InteractionVisualizer.plugin.getPluginMeta().getVersion() + " is running!",
                    NamedTextColor.BLUE));
        }
    }

    @EventHandler
    public void onCraft(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getType().equals(InventoryType.WORKBENCH)) {
            ItemStack i1 = event.getView().getItem(1);
            if (i1 == null || !i1.getType().equals(Material.BONE)) {
                return;
            }
            ItemStack i2 = event.getView().getItem(2);
            if (i2 == null || !i2.getType().equals(Material.BONE)) {
                return;
            }
            ItemStack i3 = event.getView().getItem(3);
            if (i3 == null || !i3.getType().equals(Material.BONE_BLOCK)) {
                return;
            }
            ItemStack i4 = event.getView().getItem(4);
            if (i4 == null || !i4.getType().equals(Material.BONE)) {
                return;
            }
            ItemStack i5 = event.getView().getItem(5);
            if (i5 == null || !i5.getType().equals(Material.BLAZE_ROD)) {
                return;
            }
            ItemStack i6 = event.getView().getItem(6);
            if (i6 == null || !i6.getType().equals(Material.BONE)) {
                return;
            }
            ItemStack i7 = event.getView().getItem(7);
            if (i7 == null || !i7.getType().equals(Material.BONE_BLOCK)) {
                return;
            }
            ItemStack i8 = event.getView().getItem(8);
            if (i8 == null || !i8.getType().equals(Material.BONE)) {
                return;
            }
            ItemStack i9 = event.getView().getItem(9);
            if (i9 == null || !i9.getType().equals(Material.BONE)) {
                return;
            }
            event.getView().setItem(0, bone.clone());
        }
    }

}
