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
import com.loohp.interactionvisualizer.entityholders.EntityHolderTestFactory;
import com.loohp.interactionvisualizer.entityholders.Item;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JukeBoxDisplayLabelTest {

    @Test
    void discLabelMatchesTheLegacyNameTagProfile() throws ReflectiveOperationException {
        DisplayEntity label = EntityHolderTestFactory.allocate(DisplayEntity.class);

        JukeBoxDisplay.configureLabel(label);

        assertEquals(Display.Billboard.CENTER, label.getBillboard());
        assertTrue(label.isDefaultBackground());
        assertEquals(1.0F, label.getTextScale());
        assertTrue(label.usesUnboundedTextWidth());
        assertEquals(0, label.getInterpolationDuration());
        assertEquals(0, label.getTeleportDuration());
        assertFalse(label.hasGravity());
        assertTrue(label.isInvulnerable());
        assertTrue(label.isSilent());
        assertTrue(label.isCustomNameVisible());
    }

    @Test
    void discLabelUsesTheItemAnchorWithoutMutatingIt() {
        Location item = new Location(null, 12.5, 65.0, -3.5);

        Location label = JukeBoxDisplay.labelLocation(item);

        assertNotSame(item, label);
        assertEquals(12.5, label.getX());
        assertEquals(65.55, label.getY());
        assertEquals(-3.5, label.getZ());
        assertEquals(65.0, item.getY());
    }

    @Test
    void discItemNeverUsesItsNativeNameTag() throws ReflectiveOperationException {
        Item item = EntityHolderTestFactory.allocate(Item.class);
        item.setCustomName(Component.text("Pigstep"));
        item.setCustomNameVisible(true);

        assertTrue(JukeBoxDisplay.suppressNativeName(item));
        assertNull(item.getCustomName());
        assertFalse(item.isCustomNameVisible());
        assertFalse(JukeBoxDisplay.suppressNativeName(item));
    }

    @Test
    void delayedWorkCannotReviveAReplacedVisualState() {
        Map<String, Object> scheduled = new HashMap<>();
        Map<String, Object> replacement = new HashMap<>();

        assertTrue(JukeBoxDisplay.ownsVisualState(scheduled, scheduled));
        assertFalse(JukeBoxDisplay.ownsVisualState(replacement, scheduled));
    }
}
