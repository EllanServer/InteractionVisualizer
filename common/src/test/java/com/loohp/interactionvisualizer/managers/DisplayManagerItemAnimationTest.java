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

package com.loohp.interactionvisualizer.managers;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayManagerItemAnimationTest {

    @Test
    void stationaryItemsDoNotEnterTheAnimationLoop() {
        assertFalse(DisplayManager.requiresItemAnimation(false, new Vector()));
    }

    @Test
    void gravityAndVelocityActivateTheAnimationLoop() {
        assertTrue(DisplayManager.requiresItemAnimation(true, new Vector()));
        assertTrue(DisplayManager.requiresItemAnimation(false, new Vector(0.0, 0.05, 0.0)));
    }

    @Test
    void copiesTheNmsAirMovementOrderAndPrecision() {
        Vector movement = DisplayManager.itemMovementForTick(true, new Vector(0.18, 0.15, 0.05));
        assertEquals(new Vector(0.18, 0.11, 0.05), movement);

        Vector nextVelocity = DisplayManager.itemVelocityAfterMovement(movement);
        assertEquals(movement.getX() * (double) 0.98F, nextVelocity.getX());
        assertEquals(movement.getY() * 0.98D, nextVelocity.getY());
        assertEquals(movement.getZ() * (double) 0.98F, nextVelocity.getZ());
    }
}
