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

package com.loohp.interactionvisualizer.benchmark.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeComparisonPluginTest {

    @Test
    void requestedFlagsComeFromTheControlledRuntimeProfile() {
        assertEquals(
                new RuntimeComparisonPlugin.RequestedFlags(false, false),
                RuntimeComparisonPlugin.requestedFlagsForProfile("legacy-parity"));
        assertEquals(
                new RuntimeComparisonPlugin.RequestedFlags(true, true),
                RuntimeComparisonPlugin.requestedFlagsForProfile("optimized-candidate"));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeComparisonPlugin.requestedFlagsForProfile("unknown"));
    }

    @Test
    void legacyTargetIsRecordedAsUnsupported() {
        RuntimeComparisonPlugin.EffectiveFlags flags =
                RuntimeComparisonPlugin.inspectEffectiveFlags(LegacyTarget.class);

        assertEquals("unsupported-legacy", flags.packetOnlyStatic().status());
        assertNull(flags.packetOnlyStatic().value());
        assertEquals("unsupported-legacy", flags.eventDrivenBlockUpdates().status());
        assertNull(flags.eventDrivenBlockUpdates().value());
        assertEquals(
                "{\"packetOnlyStatic\":{"
                        + "\"status\":\"unsupported-legacy\",\"value\":null,"
                        + "\"field\":\"packetOnlyStaticVirtualItems\"},"
                        + "\"eventDrivenBlockUpdates\":{"
                        + "\"status\":\"unsupported-legacy\",\"value\":null,"
                        + "\"field\":\"eventDrivenBlockUpdates\"}}",
                flags.json());
        assertDoesNotThrow(() -> RuntimeComparisonPlugin.assertOptimizationProfile(
                "A", new RuntimeComparisonPlugin.RequestedFlags(true, true), flags));
    }

    @Test
    void optimizedCandidateMustExposeTwoEnabledRuntimeFields() {
        RuntimeComparisonPlugin.EffectiveFlags enabled =
                RuntimeComparisonPlugin.inspectEffectiveFlags(EnabledCandidate.class);
        RuntimeComparisonPlugin.EffectiveFlags disabled =
                RuntimeComparisonPlugin.inspectEffectiveFlags(DisabledCandidate.class);

        assertTrue(enabled.packetOnlyStatic().value());
        assertTrue(enabled.eventDrivenBlockUpdates().value());
        assertFalse(disabled.packetOnlyStatic().value());
        assertTrue(disabled.eventDrivenBlockUpdates().value());
        RuntimeComparisonPlugin.RequestedFlags requested =
                new RuntimeComparisonPlugin.RequestedFlags(true, true);
        assertDoesNotThrow(() -> RuntimeComparisonPlugin.assertOptimizationProfile(
                "B", requested, enabled));
        assertThrows(IllegalStateException.class,
                () -> RuntimeComparisonPlugin.assertOptimizationProfile("B", requested, disabled));
    }

    @Test
    void partialOptimizationRequestIsRejected() {
        RuntimeComparisonPlugin.EffectiveFlags enabled =
                RuntimeComparisonPlugin.inspectEffectiveFlags(EnabledCandidate.class);

        assertThrows(IllegalStateException.class,
                () -> RuntimeComparisonPlugin.assertOptimizationProfile(
                        "B", new RuntimeComparisonPlugin.RequestedFlags(true, false), enabled));
    }

    public static final class LegacyTarget {
    }

    public static final class EnabledCandidate {
        public static boolean packetOnlyStaticVirtualItems = true;
        public static boolean eventDrivenBlockUpdates = true;
    }

    public static final class DisabledCandidate {
        public static boolean packetOnlyStaticVirtualItems = false;
        public static boolean eventDrivenBlockUpdates = true;
    }
}
