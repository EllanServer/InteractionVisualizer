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

import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.logging.Level;

/** Reflection loader for the optional CraftEngine culling implementation. */
public final class ViewerCullingManagerLoader {

    static final String CRAFT_ENGINE_SENTINEL =
            "net.momirealms.craftengine.core.entity.culling.Cullable";
    static final String CRAFT_ENGINE_IMPLEMENTATION =
            "com.loohp.interactionvisualizer.integration.craftengine.CraftEngineViewerCullingManager";

    private ViewerCullingManagerLoader() {
    }

    public static Optional<ViewerCullingManager> createCraftEngine(
            Plugin plugin, ViewerCullingManager.VisibilityListener listener) {
        try {
            return Optional.of(load(plugin.getClass().getClassLoader(), CRAFT_ENGINE_SENTINEL,
                    CRAFT_ENGINE_IMPLEMENTATION, plugin, listener));
        } catch (ReflectiveOperationException | LinkageError | ClassCastException exception) {
            Throwable cause = exception instanceof InvocationTargetException invocation
                    && invocation.getCause() != null ? invocation.getCause() : exception;
            plugin.getLogger().log(Level.WARNING,
                    "Could not enable CraftEngine display culling; using sent-chunk visibility only", cause);
            return Optional.empty();
        }
    }

    static ViewerCullingManager load(ClassLoader classLoader, String sentinelClass,
                                     String implementationClass, Plugin plugin,
                                     ViewerCullingManager.VisibilityListener listener)
            throws ReflectiveOperationException {
        Class.forName(sentinelClass, false, classLoader);
        return Class.forName(implementationClass, true, classLoader)
                .asSubclass(ViewerCullingManager.class)
                .getDeclaredConstructor(Plugin.class, ViewerCullingManager.VisibilityListener.class)
                .newInstance(plugin, listener);
    }
}
