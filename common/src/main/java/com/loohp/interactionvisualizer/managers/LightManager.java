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

import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Resolves the optional CraftEngine light implementation without linking the
 * core plugin classes against CraftEngine when it is absent.
 */
public final class LightManager {

    static final String CRAFT_ENGINE_SENTINEL =
            "net.momirealms.craftengine.bukkit.api.BukkitAdaptor";
    static final String CRAFT_ENGINE_IMPLEMENTATION =
            "com.loohp.interactionvisualizer.integration.craftengine.CraftEngineLightManager";

    private LightManager() {
    }

    public static Optional<ILightManager> createCraftEngine(Plugin plugin, long updatePeriod) {
        try {
            return Optional.of(load(plugin.getClass().getClassLoader(), CRAFT_ENGINE_SENTINEL,
                    CRAFT_ENGINE_IMPLEMENTATION, plugin, updatePeriod));
        } catch (ReflectiveOperationException | LinkageError | ClassCastException exception) {
            Throwable cause = exception instanceof InvocationTargetException invocation
                    && invocation.getCause() != null ? invocation.getCause() : exception;
            plugin.getLogger().log(Level.WARNING,
                    "Could not enable CraftEngine lighting; continuing without display lighting", cause);
            return Optional.empty();
        }
    }

    static ILightManager load(ClassLoader classLoader, String sentinelClass, String implementationClass,
                              Plugin plugin, long updatePeriod) throws ReflectiveOperationException {
        Class.forName(sentinelClass, false, classLoader);
        return Class.forName(implementationClass, true, classLoader)
                .asSubclass(ILightManager.class)
                .getDeclaredConstructor(Plugin.class, long.class)
                .newInstance(plugin, updatePeriod);
    }
}
