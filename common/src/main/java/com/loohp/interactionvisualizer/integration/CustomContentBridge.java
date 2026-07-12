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

package com.loohp.interactionvisualizer.integration;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Neutral boundary for optional custom-content plugins.
 * Implementations must not expose provider-owned types through this interface.
 */
public interface CustomContentBridge {

    String provider();

    Optional<NamespacedKey> customItemId(ItemStack itemStack);
}
