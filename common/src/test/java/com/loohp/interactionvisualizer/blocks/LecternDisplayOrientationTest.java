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

import org.bukkit.entity.Display;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LecternDisplayOrientationTest {

    @Test
    void bookLabelsFaceTheViewerLikeLegacyNameTags() {
        assertEquals(Display.Billboard.CENTER, LecternDisplay.labelBillboard());
    }
}
