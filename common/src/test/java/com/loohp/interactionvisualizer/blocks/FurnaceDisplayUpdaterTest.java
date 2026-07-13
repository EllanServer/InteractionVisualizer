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

package com.loohp.interactionvisualizer.blocks;

import com.loohp.interactionvisualizer.utils.ComponentFont;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FurnaceDisplayUpdaterTest {

    @Test
    void invalidOrExtremeCookTimesStayWithinTheProgressBar() {
        assertEquals(0.0D, FurnaceDisplayUpdater.scaledProgress(0, 0, 10));
        assertEquals(0.0D, FurnaceDisplayUpdater.scaledProgress(10, -1, 10));
        assertEquals(0.0D, FurnaceDisplayUpdater.scaledProgress(-20, 200, 10));
        assertEquals(5.0D, FurnaceDisplayUpdater.scaledProgress(100, 200, 10));
        assertEquals(10.0D, FurnaceDisplayUpdater.scaledProgress(Short.MAX_VALUE, 1, 10));
    }

    @Test
    void coloredProgressTextIsComparedByItsRawRenderedState() {
        Map<String, Object> values = new HashMap<>();
        String colored = "\u00a7e\u258e\u00a77\u258e";
        String plain = PlainTextComponentSerializer.plainText().serialize(ComponentFont.parseFont(
                LegacyComponentSerializer.legacySection().deserialize(colored)));

        assertEquals("\u258e\u258e", plain);
        assertNotEquals(colored, plain);
        assertTrue(FurnaceDisplayUpdater.shouldUpdateProgress(values, colored, true));
        values.put(FurnaceDisplayUpdater.PROGRESS_TEXT_KEY, colored);
        assertFalse(FurnaceDisplayUpdater.shouldUpdateProgress(values, new String(colored), true));
        assertTrue(FurnaceDisplayUpdater.shouldUpdateProgress(values, "\u00a7c\u258e\u258e", true));
        assertTrue(FurnaceDisplayUpdater.shouldUpdateProgress(values, colored, false));
        values.remove(FurnaceDisplayUpdater.PROGRESS_TEXT_KEY);
        assertTrue(FurnaceDisplayUpdater.shouldUpdateProgress(values, colored, true));
    }

}
