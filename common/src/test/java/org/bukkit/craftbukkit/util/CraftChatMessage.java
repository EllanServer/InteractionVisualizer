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

package org.bukkit.craftbukkit.util;

import net.minecraft.network.chat.Component;

public final class CraftChatMessage {

    private CraftChatMessage() {
    }

    public static Component fromJSON(String json) {
        return new Component(json);
    }
}
