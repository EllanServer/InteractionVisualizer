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
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnvilResultLabelTest {

    @Test
    void resultNameUsesAnOccludableTextDisplayAtTheLegacyHeight() throws ReflectiveOperationException {
        Location item = new Location(null, 12.5, 65.2, -2.5);
        Component name = Component.text("Renamed sword");
        DisplayEntity label = EntityHolderTestFactory.allocate(DisplayEntity.class);

        AnvilDisplay.configureResultLabel(label, name);
        Location labelLocation = AnvilDisplay.resultLabelLocation(item);

        assertEquals(name, label.getCustomName());
        assertTrue(label.isCustomNameVisible());
        assertEquals(Display.Billboard.CENTER, label.getBillboard());
        assertTrue(label.isDefaultBackground());
        assertEquals(1.0F, label.getTextScale());
        assertTrue(label.usesUnboundedTextWidth());
        assertEquals(12.5, labelLocation.getX());
        assertEquals(65.75, labelLocation.getY());
        assertEquals(-2.5, labelLocation.getZ());
        assertEquals(65.2, item.getY(), "the item location must not be mutated");
        assertEquals(0, label.getInterpolationDuration());
        assertEquals(0, label.getTeleportDuration());
    }
}
