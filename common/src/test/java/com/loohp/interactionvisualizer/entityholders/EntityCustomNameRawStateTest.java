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
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Proxy;
import java.util.UUID;

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
    void itemSkipsEqualRawTextAndInvalidatesTheSentinelForComponentAndNullSetters() {
        Item item = new Item(location());
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
    void displaySkipsEqualRawTextAndInvalidatesTheSentinelForComponentAndNullSetters() {
        DisplayEntity display = new DisplayEntity(location());
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
    void displayTreatsEqualPlainTextWithDifferentLegacyColorsAsARealChange() {
        DisplayEntity display = new DisplayEntity(location());
        assertTrue(display.updateCustomName("§a||||", true));
        int greenRevision = display.cacheCode();

        assertTrue(display.updateCustomName("§b||||", true));
        assertEquals(greenRevision + 1, display.cacheCode());
    }

    @Test
    void displayTreatsEqualPlainTextWithDifferentFontsAsARealChange() {
        DisplayEntity display = new DisplayEntity(location());
        assertTrue(display.updateCustomName("[font=minecraft:default]same", true));
        int defaultFontRevision = display.cacheCode();

        assertTrue(display.updateCustomName("[font=minecraft:uniform]same", true));
        assertEquals(defaultFontRevision + 1, display.cacheCode());
    }

    private static Location location() {
        UUID id = UUID.randomUUID();
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "getUID" -> id;
                        case "toString" -> "World[" + id + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        return new Location(world, 0.0D, 64.0D, 0.0D);
    }
}
