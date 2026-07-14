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

package net.minecraft.world.phys;

public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);
}
