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

package com.loohp.interactionvisualizer.managers;

import com.loohp.interactionvisualizer.objectholders.ILightManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LightManagerLoaderTest {

    @Test
    void loadsTheImplementationOnlyAfterItsSentinelIsVisible() throws Exception {
        ILightManager manager = LightManager.load(getClass().getClassLoader(), "java.lang.String",
                FakeLightManager.class.getName(), null, 17L);

        FakeLightManager fake = assertInstanceOf(FakeLightManager.class, manager);
        assertEquals(17L, fake.period);
    }

    @Test
    void leavesCraftEngineClassesUnresolvedWhenTheProviderIsAbsent() {
        assertThrows(ClassNotFoundException.class, () -> LightManager.load(
                getClass().getClassLoader(), LightManager.CRAFT_ENGINE_SENTINEL,
                LightManager.CRAFT_ENGINE_IMPLEMENTATION, null, 10L));
    }

    public static final class FakeLightManager implements ILightManager {

        private final long period;

        public FakeLightManager(Plugin plugin, long period) {
            this.period = period;
        }

        @Override
        public void createLight(org.bukkit.Location location, int lightlevel,
                                com.loohp.interactionvisualizer.objectholders.LightType lightType) {
        }

        @Override
        public void deleteLight(org.bukkit.Location location) {
        }
    }
}
