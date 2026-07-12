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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DroppedItemBlacklistTest {

    @Test
    void preservesLegacyNameAndMaterialRules() {
        DroppedItemBlacklist blacklist = DroppedItemBlacklist.compile(List.of(
                List.of("^ALTAR Probe.*", "NETHER_STAR"),
                List.of("^Blacklisted Item$", "*")), ignored -> {
        });

        assertFalse(blacklist.requiresCustomItemId());
        assertTrue(blacklist.matches("ALTAR Probe 1", Material.NETHER_STAR, null));
        assertFalse(blacklist.matches("ALTAR Probe 1", Material.PAPER, null));
        assertTrue(blacklist.matches("Blacklisted Item", Material.PAPER, null));
    }

    @Test
    void matchesAnExactCustomItemIdWithoutConfusingCarrierMaterials() {
        DroppedItemBlacklist blacklist = DroppedItemBlacklist.compile(List.of(
                List.of(".*", "PAPER", "mypack:ruby")), ignored -> {
        });

        assertTrue(blacklist.requiresCustomItemId());
        assertTrue(blacklist.matches("Ruby", Material.PAPER, NamespacedKey.fromString("mypack:ruby")));
        assertFalse(blacklist.matches("Ruby", Material.PAPER, NamespacedKey.fromString("mypack:sapphire")));
        assertFalse(blacklist.matches("Ruby", Material.PAPER, null));
    }

    @Test
    void invalidRulesFailClosed() {
        List<String> warnings = new ArrayList<>();
        DroppedItemBlacklist blacklist = DroppedItemBlacklist.compile(List.of(
                List.of("[", "PAPER"),
                List.of(".*", "NOT_A_MATERIAL"),
                List.of(".*", "PAPER", "Not A Key"),
                List.of(".*", "PAPER", "ruby")), warnings::add);

        assertFalse(blacklist.matches("Anything", Material.PAPER, NamespacedKey.fromString("mypack:ruby")));
        assertFalse(blacklist.requiresCustomItemId());
        assertTrue(warnings.size() == 4);
    }
}
