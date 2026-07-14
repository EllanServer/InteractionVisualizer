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
import org.bukkit.entity.Display;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrewingStandDisplayCompatibilityTest {

    @Test
    void progressLabelMatchesTheLegacyNameTagProfile() throws ReflectiveOperationException {
        DisplayEntity label = EntityHolderTestFactory.allocate(DisplayEntity.class);

        BrewingStandDisplay.configureLabel(label);

        assertEquals(Display.Billboard.CENTER, label.getBillboard());
        assertEquals(1.0F, label.getTextScale());
        assertTrue(label.isDefaultBackground());
        assertTrue(label.usesLegacyNameTagStyle());
    }
}
