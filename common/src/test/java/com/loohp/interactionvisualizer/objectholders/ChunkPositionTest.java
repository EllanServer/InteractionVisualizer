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

package com.loohp.interactionvisualizer.objectholders;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ChunkPositionTest {

    @Test
    void equalityComparesCoordinatesInsteadOfOnlyHashCodes() {
        World world = world(UUID.fromString("10000000-0000-0000-0000-000000000001"));
        ChunkPosition first = new ChunkPosition(world, 0, 17);
        ChunkPosition colliding = new ChunkPosition(world, 1, 0);

        assertEquals(first.hashCode(), colliding.hashCode());
        assertNotEquals(first, colliding);
        assertEquals(first, new ChunkPosition(world, 0, 17));
    }

    private static World world(UUID id) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getUID")) {
                        return id;
                    }
                    if (method.getName().equals("toString")) {
                        return "World[" + id + "]";
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == arguments[0];
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

}
