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

package com.loohp.interactionvisualizer.managers;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventDrivenBeeRoutingTest {

    @Test
    void pistonRoutingIncludesSourceAndDestinationForBothDirections() {
        FakeBlocks world = new FakeBlocks();
        Block piston = world.block(0, 0, 0);
        Block moved = world.block(1, 0, 0);

        Set<Block> extended = EventDrivenBlockUpdateListener.pistonAffectedBlocks(
                piston, List.of(moved), BlockFace.EAST, BlockFace.EAST);
        assertEquals(Set.of(world.block(0, 0, 0), world.block(1, 0, 0), world.block(2, 0, 0)),
                extended);

        // Bukkit supplies WEST as the actual retract movement, while the old
        // piston head that disappears remains on the outward EAST side.
        Set<Block> retracted = EventDrivenBlockUpdateListener.pistonAffectedBlocks(
                piston, List.of(world.block(2, 0, 0)), BlockFace.WEST, BlockFace.EAST);
        assertEquals(Set.of(world.block(0, 0, 0), world.block(1, 0, 0), world.block(2, 0, 0)),
                retracted);

        Set<Block> emptyRetraction = EventDrivenBlockUpdateListener.pistonAffectedBlocks(
                piston, List.of(), BlockFace.WEST, BlockFace.EAST);
        assertEquals(Set.of(world.block(0, 0, 0), world.block(1, 0, 0)), emptyRetraction);
    }

    @Test
    void overlappingChangedColumnsRouteEachBeeBlockOnceAndOnlyWithinFiveBlocks() {
        FakeBlocks world = new FakeBlocks();
        world.material(0, 2, 0, Material.STONE);
        world.material(0, 3, 0, Material.BEEHIVE);
        world.material(0, 4, 0, Material.BEE_NEST);
        world.material(0, 7, 0, Material.BEEHIVE);

        List<Block> routed = new ArrayList<>();
        EventDrivenBlockUpdateListener.scanAffectedColumns(
                List.of(world.block(0, 0, 0), world.block(0, 1, 0)), routed::add);

        assertEquals(List.of(world.block(0, 3, 0), world.block(0, 4, 0)), routed);
    }

    @Test
    void redstoneAndInteractionFiltersOnlyAdmitRelevantBlockFamilies() {
        assertTrue(EventDrivenBlockUpdateListener.isRedstoneOpenable(Material.OAK_TRAPDOOR));
        assertTrue(EventDrivenBlockUpdateListener.isRedstoneOpenable(Material.IRON_DOOR));
        assertTrue(EventDrivenBlockUpdateListener.isRedstoneOpenable(Material.OAK_FENCE_GATE));
        assertFalse(EventDrivenBlockUpdateListener.isRedstoneOpenable(Material.REDSTONE_WIRE));

        assertTrue(EventDrivenBlockUpdateListener.isSmokeColumnInteractable(Material.CAMPFIRE));
        assertTrue(EventDrivenBlockUpdateListener.isSmokeColumnInteractable(Material.SOUL_CAMPFIRE));
        assertTrue(EventDrivenBlockUpdateListener.isSmokeColumnInteractable(Material.IRON_TRAPDOOR));
        assertFalse(EventDrivenBlockUpdateListener.isSmokeColumnInteractable(Material.STONE));
    }

    private record Position(int x, int y, int z) {
    }

    private static final class FakeBlocks {

        private final Map<Position, Material> materials = new HashMap<>();
        private final Map<Position, Block> blocks = new HashMap<>();

        void material(int x, int y, int z, Material material) {
            materials.put(new Position(x, y, z), material);
        }

        Block block(int x, int y, int z) {
            Position position = new Position(x, y, z);
            return blocks.computeIfAbsent(position, ignored -> (Block) Proxy.newProxyInstance(
                    Block.class.getClassLoader(), new Class<?>[]{Block.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getType" -> materials.getOrDefault(position, Material.AIR);
                        case "getX" -> position.x();
                        case "getY" -> position.y();
                        case "getZ" -> position.z();
                        case "getRelative" -> {
                            BlockFace face = (BlockFace) arguments[0];
                            int distance = arguments.length == 1 ? 1 : (int) arguments[1];
                            yield block(position.x() + face.getModX() * distance,
                                    position.y() + face.getModY() * distance,
                                    position.z() + face.getModZ() * distance);
                        }
                        case "hashCode" -> position.hashCode();
                        case "equals" -> proxy == arguments[0];
                        case "toString" -> "FakeBlock" + position;
                        default -> throw new AssertionError("Unexpected Block method: " + method.getName());
                    }));
        }
    }
}
