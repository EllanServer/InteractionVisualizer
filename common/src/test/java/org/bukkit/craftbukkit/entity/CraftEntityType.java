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

package org.bukkit.craftbukkit.entity;

import net.minecraft.world.entity.EntityType;

public final class CraftEntityType {

    private static final EntityType<Object> TEXT_DISPLAY = new EntityType<>("text_display");

    private CraftEntityType() {
    }

    public static EntityType<?> bukkitToMinecraft(org.bukkit.entity.EntityType type) {
        if (type != org.bukkit.entity.EntityType.TEXT_DISPLAY) {
            throw new IllegalArgumentException("Unsupported test entity type: " + type);
        }
        return TEXT_DISPLAY;
    }
}
