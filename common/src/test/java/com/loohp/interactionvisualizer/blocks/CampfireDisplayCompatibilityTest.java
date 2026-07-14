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

import com.loohp.interactionvisualizer.api.events.TileEntityRemovedEvent;
import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.EntityHolderTestFactory;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampfireDisplayCompatibilityTest {

    @Test
    void progressLabelsMatchTheLegacyNameTagProfile() throws ReflectiveOperationException {
        DisplayEntity normal = EntityHolderTestFactory.allocate(DisplayEntity.class);
        DisplayEntity soul = EntityHolderTestFactory.allocate(DisplayEntity.class);

        CampfireDisplay.configureLabel(normal);
        CampfireDisplay.configureLabel(soul);

        assertLabelProfile(normal);
        assertLabelProfile(soul);
    }

    @Test
    void progressLabelsUseTheCompensatedTextAnchor() {
        Location block = new Location(null, 12.0, 64.0, -3.0);

        Location label = CampfireDisplay.labelOrigin(block);

        assertEquals(12.5, label.getX());
        assertEquals(64.3, label.getY());
        assertEquals(-2.5, label.getZ());
        assertEquals(64.0, block.getY(), "the caller's block location must remain unchanged");
    }

    @Test
    void soulCampfireCleansUpForEveryTileEntityRemoval() throws ReflectiveOperationException {
        Method handler = SoulCampfireDisplay.class.getDeclaredMethod(
                "onBreakSoulCampfire", TileEntityRemovedEvent.class);
        Method compatibleOverload = SoulCampfireDisplay.class.getDeclaredMethod(
                "onBreakSoulCampfire", BlockBreakEvent.class);

        assertTrue(handler.isAnnotationPresent(org.bukkit.event.EventHandler.class));
        assertEquals(void.class, compatibleOverload.getReturnType());
    }

    private static void assertLabelProfile(DisplayEntity label) {
        assertEquals(Display.Billboard.CENTER, label.getBillboard());
        assertEquals(1.0F, label.getTextScale());
        assertTrue(label.isDefaultBackground());
        assertTrue(label.usesLegacyNameTagStyle());
    }
}
