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

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Loads optional custom-content bridges without resolving their provider APIs
 * when the corresponding plugin is absent.
 */
public final class CustomContentManager {

    private static final String CRAFT_ENGINE_SENTINEL =
            "net.momirealms.craftengine.bukkit.api.CraftEngineItems";
    private static final String CRAFT_ENGINE_IMPLEMENTATION =
            "com.loohp.interactionvisualizer.integration.craftengine.CraftEngineCustomContentBridge";
    private static final long RUNTIME_FAILURE_RETRY_NANOS = TimeUnit.SECONDS.toNanos(5);

    private static volatile List<CustomContentBridge> bridges = List.of();
    private static volatile Plugin owner;
    private static final Set<String> failedProviders = ConcurrentHashMap.newKeySet();
    private static final Set<String> reportedRuntimeFailures = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Long> retryAfter = new ConcurrentHashMap<>();

    private CustomContentManager() {
    }

    public static synchronized Set<String> initialize(Plugin plugin) {
        owner = plugin;
        failedProviders.clear();
        reportedRuntimeFailures.clear();
        retryAfter.clear();

        List<CustomContentBridge> loaded = new ArrayList<>();
        if (Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
            loadOptional(loaded, "CraftEngine", CRAFT_ENGINE_SENTINEL, CRAFT_ENGINE_IMPLEMENTATION);
        }
        bridges = List.copyOf(loaded);
        return providers();
    }

    public static synchronized void shutdown() {
        bridges = List.of();
        failedProviders.clear();
        reportedRuntimeFailures.clear();
        retryAfter.clear();
        owner = null;
    }

    public static Set<String> providers() {
        Set<String> providers = new LinkedHashSet<>();
        for (CustomContentBridge bridge : bridges) {
            String provider = normalizeProvider(bridge.provider());
            if (!failedProviders.contains(provider)) {
                providers.add(provider);
            }
        }
        return Set.copyOf(providers);
    }

    public static Optional<NamespacedKey> customItemId(ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty()) {
            return Optional.empty();
        }
        for (CustomContentBridge bridge : bridges) {
            String provider = normalizeProvider(bridge.provider());
            if (failedProviders.contains(provider)) {
                continue;
            }
            Long retryAt = retryAfter.get(provider);
            if (retryAt != null && System.nanoTime() - retryAt < 0) {
                continue;
            }
            try {
                Optional<NamespacedKey> id = bridge.customItemId(itemStack);
                retryAfter.remove(provider);
                reportedRuntimeFailures.remove(provider);
                if (id.isPresent()) {
                    return id;
                }
            } catch (LinkageError error) {
                disable(bridge, error);
            } catch (RuntimeException exception) {
                retryAfter.put(provider, System.nanoTime() + RUNTIME_FAILURE_RETRY_NANOS);
                reportRuntimeFailure(bridge, exception);
            }
        }
        return Optional.empty();
    }

    private static void loadOptional(List<CustomContentBridge> loaded, String pluginName,
                                     String sentinelClass, String implementationClass) {
        try {
            CustomContentBridge bridge = CustomContentBridgeLoader.load(
                    owner.getClass().getClassLoader(), sentinelClass, implementationClass);
            loaded.add(bridge);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException exception) {
            owner.getLogger().log(Level.WARNING,
                    "Could not enable the optional " + pluginName + " custom-content bridge; continuing without it",
                    exception);
        }
    }

    private static void disable(CustomContentBridge bridge, Throwable exception) {
        String provider = normalizeProvider(bridge.provider());
        if (failedProviders.add(provider)) {
            Plugin plugin = owner;
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING,
                        "Disabled the " + bridge.provider() + " custom-content bridge after an API linkage failure",
                        exception);
            }
        }
    }

    private static void reportRuntimeFailure(CustomContentBridge bridge, RuntimeException exception) {
        String provider = normalizeProvider(bridge.provider());
        if (reportedRuntimeFailures.add(provider)) {
            Plugin plugin = owner;
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING,
                        "The " + bridge.provider() + " custom-content bridge failed temporarily; retrying later",
                        exception);
            }
        }
    }

    private static String normalizeProvider(String provider) {
        return provider.toLowerCase(Locale.ROOT);
    }
}
