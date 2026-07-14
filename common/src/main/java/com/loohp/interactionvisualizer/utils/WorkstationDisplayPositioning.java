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

package com.loohp.interactionvisualizer.utils;

import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.utils.MaterialUtils.MaterialMode;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/** Direct ItemDisplay positioning shared by workstation grid visuals. */
public final class WorkstationDisplayPositioning {

    /** Places fixed workstation items at the full-block top surface. */
    public static final double SURFACE_ANCHOR_Y = 1.0;

    private WorkstationDisplayPositioning() {
    }

    /**
     * Identifies workstation items without changing the public {@link Item}
     * state model used by furnaces, packet-only dropped items, banners, or
     * frames. The layout yaw is retained because side slots may deliberately
     * face away from the workstation's grid direction.
     */
    static final class WorkstationItem extends Item {

        private final float gridYaw;

        private WorkstationItem(Location location, float gridYaw) {
            super(location, RenderMode.ITEM);
            this.gridYaw = gridYaw;
        }
    }

    public static Location gridAnchor(Location blockOrigin) {
        return blockOrigin.clone().add(0.5, SURFACE_ANCHOR_Y, 0.5);
    }

    /**
     * Returns a workstation grid position relative to the block centre. Positive
     * forward offset follows the supplied yaw; positive lateral offset follows
     * the matching positive 90-degree Y rotation.
     */
    public static Location gridSlot(Location blockOrigin, float yaw, double lateralOffset, double forwardOffset) {
        Location location = gridAnchor(blockOrigin);
        location.setYaw(yaw);
        Vector forward = location.getDirection().setY(0.0).normalize();
        Vector lateral = new Vector(-forward.getZ(), 0.0, forward.getX());
        return location.add(forward.multiply(forwardOffset)).add(lateral.multiply(lateralOffset));
    }

    public static Item gridItem(Location blockOrigin, float yaw, double lateralOffset, double forwardOffset) {
        return new WorkstationItem(gridSlot(blockOrigin, yaw, lateralOffset, forwardOffset), yaw);
    }

    static boolean isWorkstationItem(Item item) {
        return item instanceof WorkstationItem;
    }

    static float gridYaw(Item item) {
        if (!(item instanceof WorkstationItem workstationItem)) {
            throw new IllegalArgumentException("Not a workstation item");
        }
        return workstationItem.gridYaw;
    }

    public static void setRenderMode(Item item, MaterialMode mode) {
        setRenderMode(item, switch (mode) {
            case ITEM -> Item.RenderMode.ITEM;
            case BLOCK -> Item.RenderMode.BLOCK;
            case LOWBLOCK -> Item.RenderMode.LOW_BLOCK;
            case TOOL -> Item.RenderMode.TOOL;
            case STANDING -> Item.RenderMode.STANDING;
        });
    }

    public static void setRenderMode(Item item, Item.RenderMode mode) {
        if (!mode.isFixedDisplay() || mode == Item.RenderMode.BANNER || mode == Item.RenderMode.FRAME) {
            throw new IllegalArgumentException("Unsupported workstation render mode: " + mode);
        }

        Item.RenderMode previous = item.getRenderMode();
        if (previous == mode) {
            return;
        }

        Location location = item.getLocation();
        float baseYaw = location.getYaw() - (usesDiagonalYaw(previous) ? 45.0F : 0.0F);
        item.setRenderMode(mode);
        item.setRotation(baseYaw + (usesDiagonalYaw(mode) ? 45.0F : 0.0F), location.getPitch());
    }

    private static boolean usesDiagonalYaw(Item.RenderMode mode) {
        return mode == Item.RenderMode.BLOCK || mode == Item.RenderMode.LOW_BLOCK;
    }
}
