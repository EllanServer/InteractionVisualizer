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

package com.loohp.interactionvisualizer.entityholders;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BillboardDisplayEntityPathTest {

    private static final double RADIUS = 0.7D;

    @Test
    void facePathSnapsToTheLegacyCardinalPlane() throws ReflectiveOperationException {
        World world = world("face");
        BillboardDisplayEntity display = display(world, DynamicVisualizerEntity.PathType.FACE);

        Location north = display.getViewingLocation(
                new Location(world, 0.1D, 4.0D, -10.0D), new Vector(0.0D, 0.0D, 1.0D));
        Location east = display.getViewingLocation(
                new Location(world, 10.0D, 4.0D, 0.1D), new Vector(0.0D, 0.0D, 1.0D));

        assertEquals(0.0D, north.getX(), 1.0E-12D);
        assertEquals(-RADIUS, north.getZ(), 1.0E-12D);
        assertEquals(RADIUS, east.getX(), 1.0E-12D);
        assertEquals(0.0D, east.getZ(), 1.0E-12D);
    }

    @Test
    void circleAndSquarePathsRetainTheirDistinctGeometry() throws ReflectiveOperationException {
        World world = world("geometry");
        Location viewer = new Location(world, 3.0D, 4.0D, 4.0D);

        Location circle = display(world, DynamicVisualizerEntity.PathType.CIRCLE)
                .getViewingLocation(viewer, new Vector(0.0D, 0.0D, 1.0D));
        Location square = display(world, DynamicVisualizerEntity.PathType.SQUARE)
                .getViewingLocation(viewer, new Vector(0.0D, 0.0D, 1.0D));

        assertEquals(0.42D, circle.getX(), 1.0E-12D);
        assertEquals(0.56D, circle.getZ(), 1.0E-12D);
        // The legacy implementation routes through Location yaw (a float),
        // which intentionally leaves a few nanometres of rounding here.
        assertEquals(0.525D, square.getX(), 1.0E-8D);
        assertEquals(0.7D, square.getZ(), 1.0E-8D);
    }

    @Test
    void viewerInsideTheRadiusUsesTheirHorizontalLookDirection()
            throws ReflectiveOperationException {
        World world = world("inside");
        BillboardDisplayEntity display = display(world, DynamicVisualizerEntity.PathType.CIRCLE);

        Location target = display.getViewingLocation(
                new Location(world, 0.1D, 4.0D, 0.1D), new Vector(1.0D, -0.5D, 0.0D));

        // Legacy recursion first moves the viewer's already-offset leveled
        // location by radius+2 along the look vector, then projects that point
        // back onto the circle.
        double length = Math.hypot(2.8D, 0.1D);
        assertEquals(2.8D / length * RADIUS, target.getX(), 1.0E-12D);
        assertEquals(0.1D / length * RADIUS, target.getZ(), 1.0E-12D);
    }

    @Test
    void dynamicTextUsesFullCameraBillboarding() throws ReflectiveOperationException {
        World world = world("billboard");
        BillboardDisplayEntity display = display(world, DynamicVisualizerEntity.PathType.FACE);

        display.useLegacyNameTagStyle();

        assertEquals(Display.Billboard.CENTER, display.getBillboard());
    }

    private static BillboardDisplayEntity display(World world, DynamicVisualizerEntity.PathType path)
            throws ReflectiveOperationException {
        BillboardDisplayEntity display = EntityHolderTestFactory.allocate(BillboardDisplayEntity.class);
        set(VisualizerEntity.class, display, "location", new Location(world, 0.0D, 0.0D, 0.0D));
        set(BillboardDisplayEntity.class, display, "radius", RADIUS);
        set(BillboardDisplayEntity.class, display, "path", path);
        return display;
    }

    private static void set(Class<?> owner, Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> primitiveDefault(method.getReturnType());
                });
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        return 0.0D;
    }
}
