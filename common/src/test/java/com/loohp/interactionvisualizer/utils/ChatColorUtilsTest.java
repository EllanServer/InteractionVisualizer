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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatColorUtilsTest {

    @Test
    void translatesLegacyAndHexCodesCaseInsensitively() {
        assertEquals("\u00a7aHello", ChatColorUtils.translateAlternateColorCodes('&', "&AHello"));
        assertEquals(
                "\u00a7x\u00a71\u00a72\u00a7A\u00a7B\u00a7E\u00a7FText",
                ChatColorUtils.translateAlternateColorCodes('&', "&x&1&2&A&B&E&FText"));
    }

    @Test
    void detectsAndCarriesFormattingSequences() {
        assertEquals("\u00a7a\u00a7l", ChatColorUtils.getFirstColors("\u00a7a\u00a7lName"));
        assertEquals("\u00a7c\u00a7l", ChatColorUtils.getLastColors("\u00a7aHello \u00a7c\u00a7lWorld"));
        assertTrue(ChatColorUtils.isLegal("\u00a7x\u00a71\u00a72\u00a73\u00a74\u00a75\u00a76"));
    }

    @Test
    void stripsCompleteHexAndLegacySequences() {
        assertEquals(
                "Value",
                ChatColorUtils.stripColor("\u00a7x\u00a71\u00a72\u00a73\u00a74\u00a75\u00a76\u00a7lValue"));
    }
}
