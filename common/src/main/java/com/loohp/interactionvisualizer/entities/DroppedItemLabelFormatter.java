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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Reload-scoped compiler for dropped-item label templates. */
final class DroppedItemLabelFormatter {

    private static final String TIMER_TOKEN = "{IV_INTERNAL_TIMER_6D42D50B}";
    private static final int MAX_SHARED_TEMPLATES = 512;
    private static final int REGULAR_VARIANT = 0;
    private static final int SINGULAR_VARIANT = 1;
    private static final int TOOLS_VARIANT = 2;

    private final String regularTemplate;
    private final String singularTemplate;
    private final String toolsTemplate;
    private final String highColor;
    private final String mediumColor;
    private final String lowColor;
    private final Map<String, Component> sharedTemplates = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Component> eldest) {
            return size() > MAX_SHARED_TEMPLATES;
        }
    };

    DroppedItemLabelFormatter(String regularTemplate, String singularTemplate,
                              String toolsTemplate, String highColor,
                              String mediumColor, String lowColor) {
        this.regularTemplate = Objects.requireNonNull(regularTemplate, "regularTemplate");
        this.singularTemplate = Objects.requireNonNull(singularTemplate, "singularTemplate");
        this.toolsTemplate = Objects.requireNonNull(toolsTemplate, "toolsTemplate");
        this.highColor = Objects.requireNonNull(highColor, "highColor");
        this.mediumColor = Objects.requireNonNull(mediumColor, "mediumColor");
        this.lowColor = Objects.requireNonNull(lowColor, "lowColor");
    }

    State state(int amount, String durability, Component itemName) {
        return new State(amount, durability, Objects.requireNonNull(itemName, "itemName"));
    }

    int sharedTemplateCount() {
        return sharedTemplates.size();
    }

    Component format(State state, int ticksLeft) {
        int secondsLeft = Math.max(0, ticksLeft / 20);
        int variant = ticksLeft >= 600 && state.durability != null
                ? TOOLS_VARIANT : state.amount == 1 ? SINGULAR_VARIANT : REGULAR_VARIANT;
        String template = switch (variant) {
            case TOOLS_VARIANT -> toolsTemplate;
            case SINGULAR_VARIANT -> singularTemplate;
            default -> regularTemplate;
        };
        boolean hasTimer = template.contains("{Timer}");
        String timerColor = hasTimer ? timerColor(secondsLeft) : null;
        if (state.compiled == null || state.compiledVariant != variant
                || !Objects.equals(state.compiledTimerColor, timerColor)) {
            compile(state, variant, template, timerColor, hasTimer);
        }
        if (!state.compiledHasTimer) {
            return state.compiled;
        }
        if (state.lastSeconds == secondsLeft && state.lastFormatted != null) {
            return state.lastFormatted;
        }
        String timerText = timerText(secondsLeft);
        Component formatted = state.compiled.replaceText(TextReplacementConfig.builder()
                .matchLiteral(TIMER_TOKEN)
                .replacement(builder -> builder.content(timerText))
                .build()).compact();
        state.lastSeconds = secondsLeft;
        state.lastFormatted = formatted;
        return formatted;
    }

    private void compile(State state, int variant, String template,
                         String timerColor, boolean hasTimer) {
        String raw = template;
        if (variant == TOOLS_VARIANT) {
            raw = raw.replace("{Durability}", state.durability);
        }
        raw = raw.replace("{Amount}", Integer.toString(state.amount));
        if (hasTimer) {
            raw = raw.replace("{Timer}", timerColor + TIMER_TOKEN);
        }
        Component parsed = sharedTemplates.computeIfAbsent(raw, LegacyTextComponentCache::parse);
        state.compiled = parsed.replaceText(TextReplacementConfig.builder()
                .matchLiteral("{Item}")
                .replacement(state.itemName)
                .build());
        state.compiledVariant = variant;
        state.compiledTimerColor = timerColor;
        state.compiledHasTimer = hasTimer;
        state.lastSeconds = Integer.MIN_VALUE;
        state.lastFormatted = null;
    }

    private String timerColor(int secondsLeft) {
        return secondsLeft <= 30 ? lowColor : secondsLeft <= 120 ? mediumColor : highColor;
    }

    static String timerText(int secondsLeft) {
        int minutes = secondsLeft / 60;
        int seconds = secondsLeft % 60;
        String minuteText = minutes < 10 ? "0" + minutes : Integer.toString(minutes);
        String secondText = seconds < 10 ? "0" + seconds : Integer.toString(seconds);
        return minuteText + ":" + secondText;
    }

    static final class State {

        private final int amount;
        private final String durability;
        private final Component itemName;
        private int compiledVariant = Integer.MIN_VALUE;
        private String compiledTimerColor;
        private boolean compiledHasTimer;
        private Component compiled;
        private int lastSeconds = Integer.MIN_VALUE;
        private Component lastFormatted;

        private State(int amount, String durability, Component itemName) {
            this.amount = amount;
            this.durability = durability;
            this.itemName = itemName;
        }
    }
}
