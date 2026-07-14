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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DroppedItemVisibilityPolicyTest {

    @Test
    void disabledCullingAndLimiterPreserveLegacyVisibility() {
        DroppedItemVisibilityPolicy policy = DroppedItemVisibilityPolicy.create(false, 64, false, 128, 32);

        assertFalse(policy.cullingEnabled());
        assertFalse(policy.rateLimitEnabled());
        assertFalse(policy.controlsPerViewerVisibility());
        assertEquals(1.0F, policy.labelViewRange());
    }

    @Test
    void eachExperimentalControlCanEnablePerViewerVisibility() {
        DroppedItemVisibilityPolicy culling = DroppedItemVisibilityPolicy.create(true, 64, false, 128, 32);
        DroppedItemVisibilityPolicy limiting = DroppedItemVisibilityPolicy.create(false, 64, true, 128, 32);

        assertTrue(culling.controlsPerViewerVisibility());
        assertTrue(limiting.controlsPerViewerVisibility());
        assertEquals(64, culling.effectiveViewDistance(96));
        assertEquals(48, culling.effectiveViewDistance(48));
    }

    @Test
    void clampsDistanceAndRepairsInvalidBucketValues() {
        DroppedItemVisibilityPolicy minimum = DroppedItemVisibilityPolicy.create(true, 1, true, 0, -1);
        DroppedItemVisibilityPolicy maximum = DroppedItemVisibilityPolicy.create(true, 4096, true, 512, 64);
        DroppedItemVisibilityPolicy repaired = DroppedItemVisibilityPolicy.create(true, 0, false, 128, 32);

        assertEquals(8, minimum.viewDistance());
        assertEquals(DroppedItemVisibilityPolicy.DEFAULT_BUCKET_SIZE, minimum.bucketSize());
        assertEquals(DroppedItemVisibilityPolicy.DEFAULT_RESTORE_PER_TICK, minimum.restorePerTick());
        assertEquals(512, maximum.viewDistance());
        assertEquals(8.0F, maximum.labelViewRange());
        assertEquals(DroppedItemVisibilityPolicy.DEFAULT_VIEW_DISTANCE, repaired.viewDistance());
    }

    @Test
    void bundledConfigurationKeepsBothControlsDisabled() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(stream);
            String config = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            int itemOptions = config.indexOf("  Item:\n");
            int villager = config.indexOf("  Villager:\n", itemOptions);
            String droppedItemOptions = config.substring(itemOptions, villager);

            assertTrue(droppedItemOptions.contains(
                    "      VisibilityCulling:\n"
                            + "        #Preserves the legacy label lifecycle unless explicitly enabled after validation\n"
                            + "        Enabled: false\n"
                            + "        ViewDistance: 64\n"));
            assertTrue(droppedItemOptions.contains(
                    "      VisibilityRateLimit:\n"
                            + "        #Independent from Settings.Performance.VisibilityRateLimit and disabled by default\n"
                            + "        Enabled: false\n"));
        }
    }
}
