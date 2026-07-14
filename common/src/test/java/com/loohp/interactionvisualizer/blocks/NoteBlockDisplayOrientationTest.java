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
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteBlockDisplayOrientationTest {

    @Test
    void noteLabelsFollowTheCameraAndUseTheLegacyBackground() throws ReflectiveOperationException {
        DisplayEntity label = EntityHolderTestFactory.allocate(DisplayEntity.class);
        int initialRevision = label.cacheCode();

        assertFalse(label.isDefaultBackground());

        NoteBlockDisplay display = new NoteBlockDisplay();
        display.setStand(label);

        assertEquals(Display.Billboard.CENTER, label.getBillboard());
        assertTrue(label.isDefaultBackground());
        assertEquals(1.0F, label.getTextScale());
        assertTrue(label.cacheCode() > initialRevision);

        int configuredRevision = label.cacheCode();
        display.setStand(label);
        assertEquals(configuredRevision, label.cacheCode());
    }

    @Test
    void textDisplayUsesTheClickedFaceLocationWithoutArmorStandCompensation() {
        Location clickedFace = new Location(null, 12.5, 64.8, -3.5);

        Location label = NoteBlockDisplay.labelLocation(clickedFace);

        assertNotSame(clickedFace, label);
        assertEquals(clickedFace.getX(), label.getX());
        assertEquals(clickedFace.getY(), label.getY());
        assertEquals(clickedFace.getZ(), label.getZ());
    }
}
