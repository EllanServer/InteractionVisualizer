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

package com.loohp.interactionvisualizer.entityholders;

import com.loohp.interactionvisualizer.utils.LegacyTextComponentCache;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("legacy-text-component-cache")
class EntityCustomNameRawStateTest {

    @BeforeEach
    void setUp() {
        LegacyTextComponentCache.stopMeasurement();
        LegacyTextComponentCache.invalidateAll();
    }

    @AfterEach
    void tearDown() {
        LegacyTextComponentCache.stopMeasurement();
        LegacyTextComponentCache.invalidateAll();
    }

    @Test
    void itemSkipsEqualRawTextAndInvalidatesTheSentinelForComponentAndNullSetters()
            throws ReflectiveOperationException {
        Item item = EntityHolderTestFactory.allocate(Item.class);
        String raw = "§aReady";

        assertTrue(item.updateCustomName(raw));
        int renderedRevision = item.cacheCode();
        assertFalse(item.updateCustomName(new String(raw)));
        assertEquals(renderedRevision, item.cacheCode());

        item.setCustomName(Component.text("override"));
        int overrideRevision = item.cacheCode();
        assertTrue(item.updateCustomName(raw));
        assertEquals(overrideRevision + 1, item.cacheCode());

        item.setCustomName(Component.text("clear me"));
        assertTrue(item.updateCustomName(null));
        assertNull(item.getCustomName());
        int nullRevision = item.cacheCode();
        assertFalse(item.updateCustomName(null));
        assertEquals(nullRevision, item.cacheCode());
    }

    @Test
    void displaySkipsEqualRawTextAndInvalidatesTheSentinelForComponentAndNullSetters()
            throws ReflectiveOperationException {
        DisplayEntity display = EntityHolderTestFactory.allocate(DisplayEntity.class);
        String raw = "§bWorking";

        assertTrue(display.updateCustomName(raw, true));
        assertTrue(display.isCustomNameVisible());
        int renderedRevision = display.cacheCode();
        assertFalse(display.updateCustomName(new String(raw), true));
        assertEquals(renderedRevision, display.cacheCode());

        display.setCustomName(Component.text("override"));
        int overrideRevision = display.cacheCode();
        assertTrue(display.updateCustomName(raw));
        assertEquals(overrideRevision + 1, display.cacheCode());

        display.setCustomName(Component.text("clear me"));
        assertTrue(display.updateCustomName(null));
        assertNull(display.getCustomName());
        int nullRevision = display.cacheCode();
        assertFalse(display.updateCustomName(null));
        assertEquals(nullRevision, display.cacheCode());
    }

    @Test
    void displayTreatsEqualPlainTextWithDifferentLegacyColorsAsARealChange()
            throws ReflectiveOperationException {
        DisplayEntity display = EntityHolderTestFactory.allocate(DisplayEntity.class);
        assertTrue(display.updateCustomName("§a||||", true));
        int greenRevision = display.cacheCode();

        assertTrue(display.updateCustomName("§b||||", true));
        assertEquals(greenRevision + 1, display.cacheCode());
    }

    @Test
    void displayTreatsEqualPlainTextWithDifferentFontsAsARealChange()
            throws ReflectiveOperationException {
        DisplayEntity display = EntityHolderTestFactory.allocate(DisplayEntity.class);
        assertTrue(display.updateCustomName("[font=minecraft:default]same", true));
        int defaultFontRevision = display.cacheCode();

        assertTrue(display.updateCustomName("[font=minecraft:uniform]same", true));
        assertEquals(defaultFontRevision + 1, display.cacheCode());
    }

}
