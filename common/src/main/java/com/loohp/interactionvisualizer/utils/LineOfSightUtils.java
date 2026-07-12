/*
 * This file is part of InteractionVisualizer.
 *
 * Copyright (C) 2025. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2025. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.loohp.interactionvisualizer.utils;

import com.loohp.interactionvisualizer.InteractionVisualizer;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public class LineOfSightUtils {

    public static boolean hasLineOfSight(Location from, Location to) {
        return hasLineOfSight(from, to, 0.01);
    }

    public static boolean hasLineOfSight(Location from, Location to, double accuracy) {
        World world = from.getWorld();
        if (world == null || !world.equals(to.getWorld())) {
            return false;
        }

        double distance = from.distance(to);
        if (distance == 0.0) {
            return true;
        }

        Vector direction = to.toVector().subtract(from.toVector()).normalize();
        RayTraceResult hit = world.rayTraceBlocks(from, direction, distance, FluidCollisionMode.NEVER, true, LineOfSightUtils::blocksSight);
        return hit == null;
    }

    private static boolean blocksSight(Block block) {
        Material type = block.getType();
        return type.isOccluding() && !InteractionVisualizer.exemptBlocks.contains(type.name());
    }

}
