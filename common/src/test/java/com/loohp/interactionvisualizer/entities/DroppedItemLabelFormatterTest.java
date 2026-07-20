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

import com.loohp.interactionvisualizer.utils.LegacyTextComponentCache;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

@ResourceLock("legacy-text-component-cache")
class DroppedItemLabelFormatterTest {

    private static final String REGULAR = "\u00A7f{Item} \u00A7bx{Amount} \u00A76[{Timer}\u00A76]";
    private static final String SINGULAR = "\u00A7f{Item} \u00A76[{Timer}\u00A76]";
    private static final String TOOLS = "\u00A7f{Item} \u00A76[{Durability}\u00A76]";
    private static final String HIGH = "\u00A7a";
    private static final String MEDIUM = "\u00A7e";
    private static final String LOW = "\u00A7c";

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
    void compiledTemplatesMatchLegacyRendering() {
        Component itemName = Component.text("Benchmark Pick")
                .color(NamedTextColor.AQUA)
                .decorate(TextDecoration.BOLD);
        DroppedItemLabelFormatter formatter = formatter(REGULAR, SINGULAR, TOOLS);

        assertEquivalent(formatter, itemName, 64, null, 12_000, REGULAR);
        assertEquivalent(formatter, itemName, 1, null, 2_400, SINGULAR);
        assertEquivalent(formatter, itemName, 1, "\u00A7a1400/1561", 12_000, TOOLS);
        assertEquivalent(formatter, itemName, 1, "\u00A7c5/1561", 599, SINGULAR);
    }

    @Test
    void preservesTimerColorForFollowingLiteralTextAndMultipleTokens() {
        String regular = "\u00A77{Item}:{Timer}tail/{Timer}/{Amount}";
        Component itemName = Component.text("Ruby", NamedTextColor.LIGHT_PURPLE);
        DroppedItemLabelFormatter formatter = formatter(regular, regular, TOOLS);

        assertEquivalent(formatter, itemName, 32, null, 2_400, regular);
        assertEquivalent(formatter, itemName, 32, null, 600, regular);
    }

    @Test
    void preservesFontTagsAroundDynamicTokens() {
        String regular = "[font=minecraft:uniform]\u00A77{Item}:{Timer}"
                + "[font=minecraft:default]/{Amount}";
        Component itemName = Component.text("Map");
        DroppedItemLabelFormatter formatter = formatter(regular, regular, TOOLS);

        assertEquivalent(formatter, itemName, 16, null, 2_400, regular);
    }

    @Test
    void reusesRenderedComponentUntilItsVisibleStateChanges() {
        Component itemName = Component.text("Stone");
        DroppedItemLabelFormatter formatter = formatter(REGULAR, SINGULAR, TOOLS);
        DroppedItemLabelFormatter.State timed = formatter.state(64, null, itemName);

        Component first = formatter.format(timed, 12_019);
        assertSame(first, formatter.format(timed, 12_000));
        assertNotSame(first, formatter.format(timed, 11_999));

        DroppedItemLabelFormatter.State tool = formatter.state(
                1, "\u00A7a1400/1561", itemName);
        Component toolFirst = formatter.format(tool, 12_000);
        assertSame(toolFirst, formatter.format(tool, 11_980));
    }

    @Test
    void parsesOnlyUniqueReloadScopedTemplates() {
        DroppedItemLabelFormatter formatter = formatter(REGULAR, SINGULAR, TOOLS);
        LegacyTextComponentCache.startMeasurement();
        DroppedItemLabelFormatter.State[] states = new DroppedItemLabelFormatter.State[128];
        for (int index = 0; index < states.length; index++) {
            int amount = index % 64 + 1;
            states[index] = formatter.state(amount, null, Component.text("Item " + index));
            formatter.format(states[index], 12_000);
        }
        for (DroppedItemLabelFormatter.State state : states) {
            formatter.format(state, 11_980);
        }

        LegacyTextComponentCache.CacheMetrics metrics = LegacyTextComponentCache.stopMeasurement();
        assertEquals(64L, metrics.requests());
        assertEquals(64L, metrics.misses());
    }

    @Test
    void reloadScopedTemplateCacheStaysBounded() {
        DroppedItemLabelFormatter formatter = formatter(REGULAR, SINGULAR, TOOLS);
        for (int amount = 2; amount < 1_026; amount++) {
            formatter.format(formatter.state(
                    amount, null, Component.text("Item " + amount)), 12_000);
        }

        assertEquals(512, formatter.sharedTemplateCount());
    }

    @Test
    void timerTextKeepsLegacyTwoDigitMinimumWidth() {
        assertEquals("00:00", DroppedItemLabelFormatter.timerText(0));
        assertEquals("09:05", DroppedItemLabelFormatter.timerText(545));
        assertEquals("100:03", DroppedItemLabelFormatter.timerText(6_003));
    }

    private static DroppedItemLabelFormatter formatter(String regular, String singular,
                                                        String tools) {
        return new DroppedItemLabelFormatter(
                regular, singular, tools, HIGH, MEDIUM, LOW);
    }

    private static void assertEquivalent(DroppedItemLabelFormatter formatter,
                                         Component itemName, int amount,
                                         String durability, int ticksLeft,
                                         String expectedTemplate) {
        Component expected = legacyFormat(
                expectedTemplate, itemName, amount, durability, ticksLeft);
        Component actual = formatter.format(
                formatter.state(amount, durability, itemName), ticksLeft);
        assertEquals(expected.compact(), actual);
    }

    private static Component legacyFormat(String template, Component itemName,
                                          int amount, String durability,
                                          int ticksLeft) {
        int secondsLeft = Math.max(0, ticksLeft / 20);
        String timerColor = secondsLeft <= 30 ? LOW : secondsLeft <= 120 ? MEDIUM : HIGH;
        String timer = timerColor + String.format(
                Locale.ROOT, "%02d:%02d", secondsLeft / 60, secondsLeft % 60);
        String rendered = template;
        if (ticksLeft >= 600 && durability != null) {
            rendered = rendered.replace("{Durability}", durability);
        }
        rendered = rendered.replace("{Amount}", Integer.toString(amount))
                .replace("{Timer}", timer);
        return LegacyTextComponentCache.parse(rendered).replaceText(
                TextReplacementConfig.builder()
                        .matchLiteral("{Item}")
                        .replacement(itemName)
                        .build());
    }
}
