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
import org.bukkit.entity.Display;
import org.bukkit.util.Vector;

/**
 * Shared billboard replacement for the former per-viewer moving hologram.
 * Client-side billboard rotation replaces O(players) teleport packets.
 */
public final class BillboardDisplayEntity extends DisplayEntity implements DynamicVisualizerEntity {

    private double radius;
    private PathType path;

    public BillboardDisplayEntity(Location location, double radius, PathType path) {
        super(location);
        this.radius = radius;
        this.path = path;
        setBillboard(Display.Billboard.VERTICAL);
    }

    @Override
    public Location getViewingLocation(Location from, Vector direction) {
        return getLocation();
    }

    @Override
    public double getRadius() {
        return radius;
    }

    @Override
    public void setRadius(double radius) {
        if (this.radius != radius) {
            this.radius = radius;
            markDirty();
        }
    }

    @Override
    public PathType getPathType() {
        return path;
    }

    @Override
    public void setPathType(PathType path) {
        if (this.path != path) {
            this.path = path;
            markDirty();
        }
    }
}
