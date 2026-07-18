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

package com.loohp.interactionvisualizer.integration.craftengine;

import com.loohp.interactionvisualizer.objectholders.LightType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestedLightLevelsTest {

    @Test
    void coalescesSkyAndBlockRequestsToTheStrongestVanillaLightBlock() {
        RequestedLightLevels levels = RequestedLightLevels.NONE
                .with(LightType.SKY, 13)
                .with(LightType.BLOCK, 7);

        assertEquals(13, levels.effective());
        assertEquals(13, levels.sky());
        assertEquals(7, levels.block());
    }

    @Test
    void aLaterRequestReplacesOnlyItsOwnChannel() {
        RequestedLightLevels levels = new RequestedLightLevels(12, 8)
                .with(LightType.BLOCK, 3);

        assertEquals(8, levels.effective());
        assertEquals(3, levels.block());
        assertEquals(8, levels.sky());
    }
}
