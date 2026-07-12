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

package com.loohp.interactionvisualizer.entities;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Immutable, fail-closed rules for dropped-item labels. */
final class DroppedItemBlacklist {

    private static final DroppedItemBlacklist EMPTY = new DroppedItemBlacklist(List.of());

    private final List<Rule> rules;
    private final boolean requiresCustomItemId;

    private DroppedItemBlacklist(List<Rule> rules) {
        this.rules = List.copyOf(rules);
        this.requiresCustomItemId = rules.stream().anyMatch(rule -> rule.customItemId() != null);
    }

    static DroppedItemBlacklist compile(List<?> entries, Consumer<String> warning) {
        if (entries == null || entries.isEmpty()) {
            return EMPTY;
        }

        List<Rule> rules = new ArrayList<>();
        for (Object value : entries) {
            if (!(value instanceof List<?> entry) || entry.isEmpty()) {
                warning.accept("Ignoring malformed dropped-item blacklist entry: " + value);
                continue;
            }

            Pattern namePattern;
            try {
                namePattern = Pattern.compile(String.valueOf(entry.getFirst()));
            } catch (PatternSyntaxException exception) {
                warning.accept("Ignoring invalid dropped-item blacklist regex: " + entry.getFirst());
                continue;
            }

            Material material = null;
            if (entry.size() > 1 && !"*".equals(String.valueOf(entry.get(1)))) {
                String materialName = String.valueOf(entry.get(1));
                material = Material.matchMaterial(materialName);
                if (material == null) {
                    warning.accept("Ignoring dropped-item blacklist entry with invalid material: " + materialName);
                    continue;
                }
            }

            NamespacedKey customItemId = null;
            if (entry.size() > 2) {
                String id = String.valueOf(entry.get(2));
                customItemId = id.indexOf(':') > 0 ? NamespacedKey.fromString(id) : null;
                if (customItemId == null) {
                    warning.accept("Ignoring dropped-item blacklist entry with invalid custom item ID: " + id);
                    continue;
                }
            }

            rules.add(new Rule(namePattern, material, customItemId));
        }
        return rules.isEmpty() ? EMPTY : new DroppedItemBlacklist(rules);
    }

    boolean requiresCustomItemId() {
        return requiresCustomItemId;
    }

    boolean matches(String name, Material material, NamespacedKey customItemId) {
        for (Rule rule : rules) {
            if (rule.matches(name, material, customItemId)) {
                return true;
            }
        }
        return false;
    }

    private record Rule(Pattern namePattern, Material material, NamespacedKey customItemId) {

        private boolean matches(String name, Material candidateMaterial, NamespacedKey candidateCustomItemId) {
            return namePattern.matcher(name).matches()
                    && (material == null || material == candidateMaterial)
                    && (customItemId == null || customItemId.equals(candidateCustomItemId));
        }
    }
}
