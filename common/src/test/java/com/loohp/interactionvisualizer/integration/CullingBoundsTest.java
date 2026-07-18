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

package com.loohp.interactionvisualizer.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CullingBoundsTest {

    @Test
    void acceptsAnUnlimitedFiniteBox() {
        assertDoesNotThrow(() -> new CullingBounds(
                -1.0D, 2.0D, -3.0D, 4.0D, 5.0D, 6.0D,
                0, 0.2D, true));
    }

    @Test
    void rejectsInvalidProviderInputsAtTheNeutralBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new CullingBounds(
                Double.NaN, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D,
                0, 0.0D, true));
        assertThrows(IllegalArgumentException.class, () -> new CullingBounds(
                2.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D,
                0, 0.0D, true));
        assertThrows(IllegalArgumentException.class, () -> new CullingBounds(
                0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D,
                -1, 0.0D, true));
        assertThrows(IllegalArgumentException.class, () -> new CullingBounds(
                0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D,
                0, -0.1D, true));
    }
}
