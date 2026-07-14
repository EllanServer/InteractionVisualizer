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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("legacy-text-component-cache")
class LegacyTextComponentCacheTest {

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
    void preservesLegacyAndFontParsingSemantics() {
        String raw = "§eReady §x§1§2§3§4§5§6[font=minecraft:default]Now§r!";
        Component expected = ComponentFont.parseFont(
                LegacyComponentSerializer.legacySection().deserialize(raw));

        assertEquals(expected, LegacyTextComponentCache.parse(raw));
    }

    @Test
    void preservesEscapedAndEmptyFontTagSemantics() {
        String raw = "\\[font=minecraft:uniform]literal[font=minecraft:uniform]styled[font=]reset";
        Component expected = ComponentFont.parseFont(
                LegacyComponentSerializer.legacySection().deserialize(raw));

        assertEquals(expected, LegacyTextComponentCache.parse(raw));
    }

    @Test
    void sharesOneImmutableComponentForTheSameRawText() {
        String firstKey = new String("§e||||§7||||");
        String equalKey = new String("§e||||§7||||");

        Component first = LegacyTextComponentCache.parse(firstKey);
        Component second = LegacyTextComponentCache.parse(equalKey);

        assertSame(first, second);
    }

    @Test
    void reportsRequestsAndObservedCacheMisses() {
        LegacyTextComponentCache.startMeasurement();

        LegacyTextComponentCache.parse("§aone");
        LegacyTextComponentCache.parse("§aone");
        LegacyTextComponentCache.parse("§btwo");
        LegacyTextComponentCache.recordSameRawFastPath();

        LegacyTextComponentCache.CacheMetrics metrics = LegacyTextComponentCache.stopMeasurement();
        assertEquals(3L, metrics.requests());
        assertEquals(2L, metrics.misses());
        assertEquals(1L, metrics.hits());
        assertEquals(1.0D / 3.0D, metrics.hitRate(), 1.0E-12D);
        assertEquals(1L, metrics.sameRawFastPaths());
    }

    @Test
    void measurementRoundsDoNotLeakCountersAcrossTheirBoundaries() {
        LegacyTextComponentCache.startMeasurement();
        LegacyTextComponentCache.parse("§afirst");
        LegacyTextComponentCache.CacheMetrics first = LegacyTextComponentCache.stopMeasurement();

        LegacyTextComponentCache.parse("§boutside");
        assertEquals(first, LegacyTextComponentCache.metrics());

        LegacyTextComponentCache.startMeasurement();
        LegacyTextComponentCache.parse("§afirst");
        LegacyTextComponentCache.CacheMetrics second = LegacyTextComponentCache.stopMeasurement();
        assertEquals(1L, second.requests());
        assertEquals(0L, second.misses());
    }

    @Test
    void bypassesOversizedOneOffText() {
        String oversized = "x".repeat(LegacyTextComponentCache.MAXIMUM_CACHEABLE_LENGTH + 1);
        LegacyTextComponentCache.startMeasurement();

        Component first = LegacyTextComponentCache.parse(oversized);
        Component second = LegacyTextComponentCache.parse(oversized);

        LegacyTextComponentCache.CacheMetrics metrics = LegacyTextComponentCache.stopMeasurement();
        assertEquals(first, second);
        assertEquals(2L, metrics.requests());
        assertEquals(2L, metrics.misses());
        assertEquals(0L, LegacyTextComponentCache.estimatedSize());
    }

    @Test
    void staysBoundedUnderHighCardinalityNames() {
        for (int i = 0; i < LegacyTextComponentCache.MAXIMUM_SIZE * 2; i++) {
            LegacyTextComponentCache.parse("unique-" + i);
        }

        assertTrue(LegacyTextComponentCache.estimatedSize() <= LegacyTextComponentCache.MAXIMUM_SIZE);
    }
}
