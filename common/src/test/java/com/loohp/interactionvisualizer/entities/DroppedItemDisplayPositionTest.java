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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DroppedItemDisplayPositionTest {

    @Test
    void compensatesForTheItemsPassengerAttachmentHeight() {
        assertEquals(0.55F, DroppedItemDisplay.mountedLabelTranslation(0.8D, 0.25D), 1.0E-6F);
    }
}
