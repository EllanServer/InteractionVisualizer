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

package com.loohp.interactionvisualizer.objectholders;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

public class TileEntity {

    public static TileEntityType getTileEntityType(Material material) {
        if (material == null) {
            return null;
        }
        return switch (material) {
            case BLAST_FURNACE -> TileEntityType.BLAST_FURNACE;
            case BREWING_STAND -> TileEntityType.BREWING_STAND;
            case FURNACE -> TileEntityType.FURNACE;
            case SMOKER -> TileEntityType.SMOKER;
            case BEACON -> TileEntityType.BEACON;
            case JUKEBOX -> TileEntityType.JUKEBOX;
            case BEE_NEST -> TileEntityType.BEE_NEST;
            case BEEHIVE -> TileEntityType.BEEHIVE;
            case LECTERN -> TileEntityType.LECTERN;
            case CAMPFIRE -> TileEntityType.CAMPFIRE;
            case SOUL_CAMPFIRE -> TileEntityType.SOUL_CAMPFIRE;
            case SPAWNER -> TileEntityType.SPAWNER;
            case TRIAL_SPAWNER -> TileEntityType.TRIAL_SPAWNER;
            case CONDUIT -> TileEntityType.CONDUIT;
            case CRAFTER -> TileEntityType.CRAFTER;
            case WHITE_BANNER, ORANGE_BANNER, MAGENTA_BANNER, LIGHT_BLUE_BANNER,
                    YELLOW_BANNER, LIME_BANNER, PINK_BANNER, GRAY_BANNER,
                    LIGHT_GRAY_BANNER, CYAN_BANNER, PURPLE_BANNER, BLUE_BANNER,
                    BROWN_BANNER, GREEN_BANNER, RED_BANNER, BLACK_BANNER,
                    WHITE_WALL_BANNER, ORANGE_WALL_BANNER, MAGENTA_WALL_BANNER,
                    LIGHT_BLUE_WALL_BANNER, YELLOW_WALL_BANNER, LIME_WALL_BANNER,
                    PINK_WALL_BANNER, GRAY_WALL_BANNER, LIGHT_GRAY_WALL_BANNER,
                    CYAN_WALL_BANNER, PURPLE_WALL_BANNER, BLUE_WALL_BANNER,
                    BROWN_WALL_BANNER, GREEN_WALL_BANNER, RED_WALL_BANNER,
                    BLACK_WALL_BANNER -> TileEntityType.BANNER;
            default -> null;
        };
    }

    public static boolean isTileEntityType(Material material) {
        return getTileEntityType(material) != null;
    }

    private final World world;
    private final int x;
    private final int y;
    private final int z;
    private final TileEntityType type;

    public TileEntity(World world, int x, int y, int z, TileEntityType type) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.type = type;
    }

    public World getWorld() {
        return world;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public TileEntityType getType() {
        return type;
    }

    public Block getBlock() {
        return world.getBlockAt(x, y, z);
    }

    public enum TileEntityType {

        BLAST_FURNACE,
        BREWING_STAND,
        FURNACE,
        SMOKER,
        BEACON,
        JUKEBOX,
        BEE_NEST,
        BEEHIVE,
        LECTERN,
        CAMPFIRE,
        SOUL_CAMPFIRE,
        SPAWNER,
        TRIAL_SPAWNER,
        CONDUIT,
        BANNER,
        CRAFTER

    }

}
