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
 * Text-display replacement for the former per-viewer moving hologram.
 * Viewer positions are calculated exactly as the legacy surrounding-plane
 * marker entity and synchronized only after that viewer actually moves.
 */
public final class BillboardDisplayEntity extends DisplayEntity implements DynamicVisualizerEntity {

    private static final double RIGHT_ANGLE = Math.PI / 2.0D;
    private static final double FORTY_FIVE_DEGREES = RIGHT_ANGLE / 2.0D;

    private double radius;
    private PathType path;

    public BillboardDisplayEntity(Location location, double radius, PathType path) {
        super(location);
        this.radius = radius;
        this.path = path;
        setBillboard(Display.Billboard.CENTER);
    }

    @Override
    public Location getViewingLocation(Location from, Vector direction) {
        Location location = getLocation();
        if (!from.getWorld().equals(location.getWorld())) {
            throw new IllegalArgumentException("Cannot view billboard in "
                    + location.getWorld().getName() + " from " + from.getWorld().getName());
        }
        return location.add(getViewingVector(location, from, direction, radius, path));
    }

    private static Vector getViewingVector(Location location, Location from, Vector direction,
                                           double radius, PathType path) {
        Location leveled = from.clone();
        leveled.setY(location.getY());
        if (location.distanceSquared(leveled) < radius * radius) {
            Vector vector = direction.clone().setY(0.0D);
            if (vector.getX() == 0.0D && vector.getZ() == 0.0D) {
                vector.setX(0.001D);
            }
            vector.normalize().multiply(radius + 2.0D);
            return getViewingVector(location, leveled.add(vector), direction, radius, path);
        }

        Vector vector;
        switch (path) {
            case SQUARE:
                Vector axis = location.clone().add(1.0D, 0.0D, 0.0D).toVector()
                        .subtract(location.toVector()).normalize();
                vector = leveled.toVector().subtract(location.toVector()).normalize();
                double rawAngle = Math.abs(axis.angle(vector));
                double angle = rawAngle % FORTY_FIVE_DEGREES;
                if (rawAngle % RIGHT_ANGLE > FORTY_FIVE_DEGREES) {
                    angle = FORTY_FIVE_DEGREES - angle;
                }
                vector.multiply(radius / Math.cos(angle));
                break;
            case CIRCLE:
                vector = leveled.toVector().subtract(location.toVector()).normalize().multiply(radius);
                break;
            case FACE:
            default:
                Vector facing = leveled.toVector().subtract(location.toVector()).normalize();
                Location origin = location.clone().setDirection(facing);
                origin.setYaw(getCardinalDirection(origin));
                vector = origin.getDirection().normalize().multiply(radius);
                break;
        }
        return vector;
    }

    static float getCardinalDirection(Location location) {
        double rotation = (location.getYaw() - 90.0F) % 360.0F;
        if (rotation < 0.0D) {
            rotation += 360.0D;
        }
        if (rotation < 45.0D) {
            return 90.0F;
        }
        if (rotation < 135.0D) {
            return 180.0F;
        }
        if (rotation < 225.0D) {
            return -90.0F;
        }
        if (rotation < 315.0D) {
            return 0.0F;
        }
        return 90.0F;
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
