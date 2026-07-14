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

package com.loohp.interactionvisualizer.utils;

import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.entityholders.EntityHolderTestFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyTextComponentCacheDisabledTest {

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
    void disablePropertyBypassesTheSharedCache() {
        assertFalse(LegacyTextComponentCache.isEnabled());
        LegacyTextComponentCache.invalidateAll();
        LegacyTextComponentCache.startMeasurement();

        LegacyTextComponentCache.parse("§auncached");
        LegacyTextComponentCache.parse("§auncached");

        LegacyTextComponentCache.CacheMetrics metrics = LegacyTextComponentCache.stopMeasurement();
        assertEquals(2L, metrics.requests());
        assertEquals(2L, metrics.misses());
        assertEquals(0L, metrics.hits());
        assertEquals(0L, LegacyTextComponentCache.estimatedSize());
    }

    @Test
    void disablePropertyAlsoBypassesThePerEntityRawFastPath()
            throws ReflectiveOperationException {
        Item item = EntityHolderTestFactory.allocate(Item.class);
        LegacyTextComponentCache.startMeasurement();

        assertTrue(item.updateCustomName("§buncached"));
        int revision = item.cacheCode();
        assertFalse(item.updateCustomName(new String("§buncached")));

        LegacyTextComponentCache.CacheMetrics metrics = LegacyTextComponentCache.stopMeasurement();
        assertEquals(revision, item.cacheCode());
        assertEquals(2L, metrics.requests());
        assertEquals(2L, metrics.misses());
        assertEquals(0L, metrics.sameRawFastPaths());
    }

}
