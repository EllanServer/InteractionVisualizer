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
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomContentBridgeLoaderTest {

    @Test
    void loadsANeutralBridgeOnlyAfterItsSentinelIsVisible() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        CustomContentBridge bridge = CustomContentBridgeLoader.load(
                loader, "java.lang.String", FakeBridge.class.getName());

        assertEquals("fake", bridge.provider());
    }

    @Test
    void providerClassesRemainUnresolvedWhenTheOptionalApiIsAbsent() {
        ClassLoader loader = getClass().getClassLoader();
        assertThrows(ClassNotFoundException.class, () -> CustomContentBridgeLoader.load(
                loader,
                "net.momirealms.craftengine.bukkit.api.CraftEngineItems",
                "com.loohp.interactionvisualizer.integration.craftengine.CraftEngineCustomContentBridge"));
    }

    @Test
    void rejectsImplementationsOutsideTheNeutralBridgeContract() {
        ClassLoader loader = getClass().getClassLoader();
        assertThrows(ClassCastException.class, () -> CustomContentBridgeLoader.load(
                loader, "java.lang.String", "java.lang.String"));
    }

    public static final class FakeBridge implements CustomContentBridge {

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public Optional<NamespacedKey> customItemId(ItemStack itemStack) {
            return Optional.empty();
        }
    }
}
