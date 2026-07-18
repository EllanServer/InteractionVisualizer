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

record RequestedLightLevels(int block, int sky) {

    static final RequestedLightLevels NONE = new RequestedLightLevels(0, 0);

    RequestedLightLevels with(LightType type, int level) {
        return switch (type) {
            case BLOCK -> new RequestedLightLevels(level, sky);
            case SKY -> new RequestedLightLevels(block, level);
        };
    }

    int effective() {
        return Math.max(block, sky);
    }
}
