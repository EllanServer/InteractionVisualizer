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

package com.loohp.interactionvisualizer.integration.craftengine;

import com.loohp.interactionvisualizer.integration.CustomContentBridge;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/** The only class allowed to link directly against the CraftEngine API. */
public final class CraftEngineCustomContentBridge implements CustomContentBridge {

    public CraftEngineCustomContentBridge() {
        try {
            var lookup = CraftEngineItems.class.getMethod("getCustomItemId", ItemStack.class);
            lookup.getReturnType().getMethod("asString");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("The installed CraftEngine API is not compatible", exception);
        }
    }

    @Override
    public String provider() {
        return "craftengine";
    }

    @Override
    public Optional<NamespacedKey> customItemId(ItemStack itemStack) {
        var id = CraftEngineItems.getCustomItemId(itemStack);
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(NamespacedKey.fromString(id.asString()));
    }
}
