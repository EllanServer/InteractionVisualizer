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

package com.loohp.interactionvisualizer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparrowConfigurationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void mergesMissingDefaultsWithoutOverwritingUserValues() throws Exception {
        Path file = temporaryDirectory.resolve("config.yml");
        Files.writeString(file, "existing: 7\nsection:\n  current: user\n", StandardCharsets.UTF_8);

        String defaults = "existing: 3\n"
                + "section:\n"
                + "  current: default\n"
                + "  added: true\n"
                + "list:\n"
                + "  - alpha\n"
                + "  - beta\n";

        SparrowConfiguration configuration = new SparrowConfiguration(
                file.toFile(),
                new ByteArrayInputStream(defaults.getBytes(StandardCharsets.UTF_8)),
                true);

        assertEquals(7, configuration.getInt("existing"));
        assertEquals("user", configuration.getString("section.current"));
        assertTrue(configuration.getBoolean("section.added"));
        assertEquals(java.util.List.of("alpha", "beta"), configuration.getStringList("list"));
        assertTrue(configuration.isConfigurationSection("section"));
        assertEquals(
                Map.of("current", "user", "added", true),
                configuration.getConfigurationSection("section").getValues(false));
    }

    @Test
    void snapshotsMutationsAndReloadsFromDisk() throws Exception {
        Path file = temporaryDirectory.resolve("runtime.yml");
        Files.writeString(file, "value: 1\n", StandardCharsets.UTF_8);
        SparrowConfiguration configuration = new SparrowConfiguration(file.toFile());

        configuration.set("nested.value", 42);
        assertEquals(42, configuration.getInt("nested.value"));
        assertTrue(configuration.contains("nested"));
        configuration.save();

        Files.writeString(file, "value: 9\nflag: false\n", StandardCharsets.UTF_8);
        configuration.reload();

        assertEquals(9, configuration.getInt("value"));
        assertFalse(configuration.getBoolean("flag"));
        assertFalse(configuration.contains("nested.value"));
    }

    @Test
    void replacesInvalidScalarWithDefaultSection() throws Exception {
        Path file = temporaryDirectory.resolve("invalid-section.yml");
        Files.writeString(file, "section: invalid\n", StandardCharsets.UTF_8);

        SparrowConfiguration configuration = new SparrowConfiguration(
                file.toFile(),
                new ByteArrayInputStream("section:\n  enabled: true\n  period: 20\n"
                        .getBytes(StandardCharsets.UTF_8)),
                true);

        assertTrue(configuration.isConfigurationSection("section"));
        assertTrue(configuration.getBoolean("section.enabled"));
        assertEquals(20, configuration.getInt("section.period"));
    }

    @Test
    void preservesDotsInImmediateSectionKeys() throws Exception {
        Path file = temporaryDirectory.resolve("dotted-keys.yml");
        Files.writeString(file,
                "enchantments:\n  'myplugin:life.steal': Life Steal\n  'myplugin:frost': Frost\n",
                StandardCharsets.UTF_8);

        SparrowConfiguration configuration = new SparrowConfiguration(file.toFile());

        assertEquals(
                Map.of("myplugin:life.steal", "Life Steal", "myplugin:frost", "Frost"),
                configuration.getConfigurationSection("enchantments").getValues(false));
    }
}
