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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FurnaceDisplayUpdaterTest {

    @Test
    void invalidOrExtremeCookTimesStayWithinTheProgressBar() {
        assertEquals(0.0D, FurnaceDisplayUpdater.scaledProgress(0, 0, 10));
        assertEquals(0.0D, FurnaceDisplayUpdater.scaledProgress(10, -1, 10));
        assertEquals(0.0D, FurnaceDisplayUpdater.scaledProgress(-20, 200, 10));
        assertEquals(5.0D, FurnaceDisplayUpdater.scaledProgress(100, 200, 10));
        assertEquals(10.0D, FurnaceDisplayUpdater.scaledProgress(Short.MAX_VALUE, 1, 10));
    }

}
