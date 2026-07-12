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
 */

package com.loohp.interactionvisualizer.entityholders;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;

/** Logical item-frame state rendered by a Paper ItemDisplay. */
public class ItemFrame extends VisualizerEntity {

    private ItemStack item;
    private BlockFace facing;
    private int frameRotation;

    public ItemFrame(Location location) {
        super(location);
        this.item = ItemStack.empty();
        this.facing = BlockFace.SOUTH;
    }

    public BlockFace getAttachedFace() {
        return facing;
    }

    public float getYaw() {
        return switch (facing) {
            case EAST -> -90.0F;
            case NORTH -> 180.0F;
            case WEST -> 90.0F;
            default -> 0.0F;
        };
    }

    public float getPitch() {
        return switch (facing) {
            case DOWN -> 90.0F;
            case UP -> -90.0F;
            default -> 0.0F;
        };
    }

    public ItemStack getItem() {
        return item.clone();
    }

    public void setItem(ItemStack item) {
        ItemStack normalized = item == null || item.getType() == Material.AIR ? ItemStack.empty() : item.clone();
        if (!this.item.equals(normalized)) {
            this.item = normalized;
            markDirty();
        }
    }

    public BlockFace getFacingDirection() {
        return facing;
    }

    public void setFacingDirection(BlockFace facing) {
        if (facing == null) {
            throw new IllegalArgumentException("Facing cannot be null");
        }
        if (this.facing != facing) {
            this.facing = facing;
            markDirty();
        }
    }

    public int getFrameRotation() {
        return frameRotation;
    }

    public void setFrameRotation(int rotation) {
        if (rotation < 0 || rotation > 7) {
            throw new IllegalArgumentException("Item frame rotation must be between 0 and 7");
        }
        if (frameRotation != rotation) {
            frameRotation = rotation;
            markDirty();
        }
    }

    @Override
    public double getHeight() {
        return 0.75;
    }
}
