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

import com.loohp.interactionvisualizer.objectholders.ChunkPosition;
import com.loohp.interactionvisualizer.objectholders.TileEntity.TileEntityType;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TileEntityManagerLifecycleOrderTest {

    @Test
    void removesTheStaleTypeBeforeAddingTheReplacement() {
        Map<TileEntityType, Set<String>> active = emptyActiveSets();
        Map<String, TileEntityType> lastActive = new HashMap<>();
        List<String> events = new ArrayList<>();
        active.get(TileEntityType.FURNACE).add("block");
        lastActive.put("block", TileEntityType.FURNACE);

        TileEntityManager.reconcileActiveType(
                "block", TileEntityType.BLAST_FURNACE, true, true,
                active, lastActive,
                (value, change, type) -> events.add(change + ":" + type));

        assertEquals(List.of(
                "REMOVED:FURNACE",
                "ADDED:BLAST_FURNACE"), events);
        assertFalse(active.get(TileEntityType.FURNACE).contains("block"));
        assertTrue(active.get(TileEntityType.BLAST_FURNACE).contains("block"));
        assertEquals(TileEntityType.BLAST_FURNACE, lastActive.get("block"));
    }

    @Test
    void reactivationOfTheSameTypeKeepsItsDistinctLifecycleSignal() {
        Map<TileEntityType, Set<String>> active = emptyActiveSets();
        Map<String, TileEntityType> lastActive = new HashMap<>();
        List<String> events = new ArrayList<>();
        lastActive.put("block", TileEntityType.BEEHIVE);

        TileEntityManager.reconcileActiveType(
                "block", TileEntityType.BEEHIVE, true, true,
                active, lastActive,
                (value, change, type) -> events.add(change + ":" + type));

        assertEquals(List.of("ACTIVATED:BEEHIVE"), events);
        assertTrue(active.get(TileEntityType.BEEHIVE).contains("block"));
    }

    @Test
    void removalClearsTheLastActiveTypeBeforeDispatchingTheRemoval() {
        Map<TileEntityType, Set<String>> active = emptyActiveSets();
        Map<String, TileEntityType> lastActive = new HashMap<>();
        List<String> events = new ArrayList<>();
        active.get(TileEntityType.BEE_NEST).add("block");
        lastActive.put("block", TileEntityType.BEE_NEST);

        TileEntityManager.reconcileActiveType(
                "block", null, true, true,
                active, lastActive,
                (value, change, type) -> {
                    assertFalse(lastActive.containsKey(value));
                    events.add(change + ":" + type);
                });

        assertEquals(List.of("REMOVED:BEE_NEST"), events);
        assertFalse(lastActive.containsKey("block"));
    }

    @Test
    void inactiveCurrentTypeIsNotAddedAndPreservesTheLastActiveType() {
        Map<TileEntityType, Set<String>> active = emptyActiveSets();
        Map<String, TileEntityType> lastActive = new HashMap<>();
        List<String> events = new ArrayList<>();
        lastActive.put("block", TileEntityType.BEEHIVE);

        TileEntityManager.reconcileActiveType(
                "block", TileEntityType.BEEHIVE, false, true,
                active, lastActive,
                (value, change, type) -> events.add(change + ":" + type));

        assertTrue(events.isEmpty());
        assertFalse(active.get(TileEntityType.BEEHIVE).contains("block"));
        assertEquals(TileEntityType.BEEHIVE, lastActive.get("block"));
    }

    @Test
    void legacyModeReplacesTheActiveTypeWithoutTrackingAnAddedEvent() {
        Map<TileEntityType, Set<String>> active = emptyActiveSets();
        Map<String, TileEntityType> lastActive = new HashMap<>();
        List<String> events = new ArrayList<>();
        active.get(TileEntityType.FURNACE).add("block");

        TileEntityManager.reconcileActiveType(
                "block", TileEntityType.BLAST_FURNACE, true, false,
                active, lastActive,
                (value, change, type) -> events.add(change + ":" + type));

        assertEquals(List.of("REMOVED:FURNACE"), events);
        assertFalse(active.get(TileEntityType.FURNACE).contains("block"));
        assertTrue(active.get(TileEntityType.BLAST_FURNACE).contains("block"));
        assertTrue(lastActive.isEmpty());
    }

    @Test
    void chunkIndexOnlyRetainsNonEmptyEntries() {
        Map<String, Set<String>> index = new HashMap<>();
        Set<String> empty = new HashSet<>();
        Set<String> occupied = new HashSet<>(Set.of("block"));
        index.put("empty", empty);
        index.put("occupied", occupied);

        TileEntityManager.updateNonEmptyIndex(index, "empty", empty);
        TileEntityManager.updateNonEmptyIndex(index, "occupied", occupied);
        TileEntityManager.updateNonEmptyIndex(index, "detached", new HashSet<>(Set.of("block")));

        assertFalse(index.containsKey("empty"));
        assertSame(occupied, index.get("occupied"));
        assertFalse(index.containsKey("detached"));
    }

    @Test
    void unloadReloadReentryCannotReviveDetachedChunkGeneration() {
        Map<String, Set<String>> index = new HashMap<>();
        Set<String> indexedBlocks = new HashSet<>(Set.of("block"));
        Set<String> reloadedBlocks = new HashSet<>(Set.of("block"));
        index.put("chunk", indexedBlocks);
        Map<TileEntityType, Set<String>> active = emptyActiveSets();
        Map<String, TileEntityType> lastActive = new HashMap<>();
        List<String> events = new ArrayList<>();
        active.get(TileEntityType.FURNACE).add("block");
        lastActive.put("block", TileEntityType.FURNACE);

        boolean stillCurrent = TileEntityManager.reconcileActiveTypeIfCurrent(
                index, "chunk", indexedBlocks,
                "block", TileEntityType.BLAST_FURNACE, true, true,
                active, lastActive,
                (value, change, type) -> {
                    events.add(change + ":" + type);
                    if (change == TileEntityManager.LifecycleChange.REMOVED) {
                        index.remove("chunk");
                        for (Set<String> values : active.values()) {
                            values.removeAll(indexedBlocks);
                        }
                        lastActive.keySet().removeAll(indexedBlocks);
                        index.put("chunk", reloadedBlocks);
                        active.get(TileEntityType.BEE_NEST).add("block");
                        lastActive.put("block", TileEntityType.BEE_NEST);
                    }
                });
        TileEntityManager.updateNonEmptyIndex(index, "chunk", indexedBlocks);

        assertFalse(stillCurrent);
        assertSame(reloadedBlocks, index.get("chunk"));
        assertFalse(active.get(TileEntityType.FURNACE).contains("block"));
        assertFalse(active.get(TileEntityType.BLAST_FURNACE).contains("block"));
        assertTrue(active.get(TileEntityType.BEE_NEST).contains("block"));
        assertEquals(TileEntityType.BEE_NEST, lastActive.get("block"));
        assertEquals(List.of("REMOVED:FURNACE"), events);
    }

    @Test
    void staleEmptyGenerationCannotRemoveReloadedIndex() {
        Map<String, Set<String>> index = new HashMap<>();
        Set<String> stale = new HashSet<>();
        Set<String> reloaded = new HashSet<>();
        index.put("chunk", reloaded);

        TileEntityManager.updateNonEmptyIndex(index, "chunk", stale);

        assertSame(reloaded, index.get("chunk"));
    }

    @Test
    void everyScanInstallsANewGenerationEvenWhenContentsMatch() {
        Map<String, Set<String>> index = new HashMap<>();
        Set<String> previous = new LinkedHashSet<>(Set.of("block"));
        index.put("chunk", previous);

        Set<String> current = TileEntityManager.installPendingIndexGeneration(
                index, "chunk", Set.of("block"));

        assertNotSame(previous, current);
        assertSame(current, index.get("chunk"));
        assertEquals(previous, current);
    }

    @Test
    void pendingGenerationKeepsOldBlocksVisibleUntilReconciliationFinishes() {
        Map<String, Set<String>> index = new HashMap<>();
        index.put("chunk", new LinkedHashSet<>(Set.of("old")));

        Set<String> generation = TileEntityManager.installPendingIndexGeneration(
                index, "chunk", Set.of("new"));

        assertEquals(Set.of("old", "new"), generation);
        assertTrue(TileEntityManager.finishIndexGeneration(
                index, "chunk", generation, Set.of("new")));
        assertEquals(Set.of("new"), index.get("chunk"));
    }

    @Test
    void unloadMarkerRejectsNestedScanWithoutReplacingTheAuthoritativeGeneration() {
        Map<String, Set<String>> index = new HashMap<>();
        Set<String> unloadingGeneration = new LinkedHashSet<>(List.of("first", "second"));
        index.put("chunk", unloadingGeneration);

        Set<String> nestedGeneration = TileEntityManager.installPendingIndexGeneration(
                index, Set.of("chunk"), "chunk", Set.of("replacement"));

        assertNull(nestedGeneration);
        assertSame(unloadingGeneration, index.get("chunk"));
    }

    @Test
    void nestedScanDuringDeactivationStillClearsEveryPendingBlock() {
        Map<String, Set<String>> index = new HashMap<>();
        Set<String> outerGeneration = new LinkedHashSet<>(List.of("first", "second"));
        index.put("chunk", outerGeneration);
        Map<TileEntityType, Set<String>> active = emptyActiveSets();
        active.get(TileEntityType.FURNACE).addAll(outerGeneration);
        List<String> events = new ArrayList<>();

        boolean outerCurrent = TileEntityManager.deactivateIfCurrent(
                index, "chunk", outerGeneration, active,
                (value, change, type) -> {
                    events.add(value + ":" + change + ":" + type);
                    if (value.equals("first")) {
                        Set<String> nestedGeneration = TileEntityManager.installPendingIndexGeneration(
                                index, "chunk", Set.of("first", "second"));
                        assertTrue(TileEntityManager.deactivateIfCurrent(
                                index, "chunk", nestedGeneration, active,
                                (nestedBlock, nestedChange, nestedType) ->
                                        events.add(nestedBlock + ":" + nestedChange + ":" + nestedType)));
                    }
                });

        assertFalse(outerCurrent);
        assertTrue(active.get(TileEntityType.FURNACE).isEmpty());
        assertEquals(List.of(
                "first:DEACTIVATED:FURNACE",
                "second:DEACTIVATED:FURNACE"), events);
    }

    @Test
    void reentrantWatcherUpdateCommitsCountsBeforeAnyLifecycleSideEffects() {
        Map<String, Set<String>> watched = new HashMap<>();
        watched.put("player", new LinkedHashSet<>(List.of("a", "d")));
        Map<String, Integer> counts = new HashMap<>();
        counts.put("a", 1);
        counts.put("d", 1);
        Set<String> dirty = new LinkedHashSet<>();

        TileEntityManager.commitWatcherUpdate(
                watched, counts, dirty, "player", Set.of("b"));
        TileEntityManager.commitWatcherUpdate(
                watched, counts, dirty, "player", Set.of("c"));

        assertEquals(Set.of("c"), watched.get("player"));
        assertEquals(Map.of("c", 1), counts);
        assertEquals(Set.of("a", "d", "b", "c"), dirty);
    }

    @Test
    void adjacentWatcherShiftMatchesAFullWindowReplacement() {
        World world = world(UUID.randomUUID());
        Set<ChunkPosition> previous = chunkWindow(world, 10, -4, 1);
        Map<String, Set<ChunkPosition>> watched = new HashMap<>();
        watched.put("player", new LinkedHashSet<>(previous));
        Map<ChunkPosition, Integer> counts = new HashMap<>();
        previous.forEach(chunk -> counts.put(chunk, 1));
        Set<ChunkPosition> dirty = new LinkedHashSet<>();

        int candidates = TileEntityManager.shiftWatchedWindow(
                watched, counts, dirty, "player", world,
                10, -4, 11, -3, 1);

        Set<ChunkPosition> expected = chunkWindow(world, 11, -3, 1);
        Set<ChunkPosition> changed = new HashSet<>(previous);
        changed.removeAll(expected);
        Set<ChunkPosition> entered = new HashSet<>(expected);
        entered.removeAll(previous);
        changed.addAll(entered);
        assertEquals(expected, watched.get("player"));
        assertEquals(expected, counts.keySet());
        assertTrue(counts.values().stream().allMatch(count -> count == 1));
        assertEquals(changed, dirty);
        assertEquals(5, candidates);
    }

    @Test
    void nestedSameChunkScanCannotBeOverwrittenByOuterStaleType() {
        Map<String, Set<String>> index = new HashMap<>();
        Set<String> initial = new LinkedHashSet<>(Set.of("block"));
        index.put("chunk", initial);
        Set<String> outerGeneration = TileEntityManager.installPendingIndexGeneration(
                index, "chunk", Set.of("block"));
        Map<TileEntityType, Set<String>> active = emptyActiveSets();
        Map<String, TileEntityType> lastActive = new HashMap<>();
        List<String> events = new ArrayList<>();
        active.get(TileEntityType.FURNACE).add("block");
        lastActive.put("block", TileEntityType.FURNACE);

        boolean outerCurrent = TileEntityManager.reconcileActiveTypeIfCurrent(
                index, "chunk", outerGeneration,
                "block", TileEntityType.BLAST_FURNACE, true, true,
                active, lastActive,
                (value, change, type) -> {
                    events.add(change + ":" + type);
                    if (change == TileEntityManager.LifecycleChange.REMOVED) {
                        Set<String> nestedGeneration = TileEntityManager.installPendingIndexGeneration(
                                index, "chunk", Set.of("block"));
                        assertTrue(TileEntityManager.reconcileActiveTypeIfCurrent(
                                index, "chunk", nestedGeneration,
                                "block", TileEntityType.BEE_NEST, true, true,
                                active, lastActive,
                                (nestedValue, nestedChange, nestedType) ->
                                        events.add(nestedChange + ":" + nestedType)));
                    }
                });

        assertFalse(outerCurrent);
        assertFalse(active.get(TileEntityType.FURNACE).contains("block"));
        assertFalse(active.get(TileEntityType.BLAST_FURNACE).contains("block"));
        assertTrue(active.get(TileEntityType.BEE_NEST).contains("block"));
        assertEquals(TileEntityType.BEE_NEST, lastActive.get("block"));
        assertEquals(List.of("REMOVED:FURNACE", "ADDED:BEE_NEST"), events);
    }

    @Test
    void nestedScanStillCleansOldBlocksTheOuterScanHasNotVisited() {
        Map<String, Set<String>> index = new HashMap<>();
        index.put("chunk", new LinkedHashSet<>(List.of("first", "second")));
        Set<String> outerGeneration = TileEntityManager.installPendingIndexGeneration(
                index, "chunk", Set.of());
        Map<TileEntityType, Set<String>> active = emptyActiveSets();
        Map<String, TileEntityType> lastActive = new HashMap<>();
        List<String> events = new ArrayList<>();
        active.get(TileEntityType.FURNACE).addAll(List.of("first", "second"));
        lastActive.put("first", TileEntityType.FURNACE);
        lastActive.put("second", TileEntityType.FURNACE);

        boolean outerCurrent = TileEntityManager.reconcileActiveTypeIfCurrent(
                index, "chunk", outerGeneration,
                "first", null, true, true,
                active, lastActive,
                (value, change, type) -> {
                    events.add(value + ":" + change + ":" + type);
                    Set<String> nestedGeneration = TileEntityManager.installPendingIndexGeneration(
                            index, "chunk", Set.of());
                    for (String nestedValue : new LinkedHashSet<>(nestedGeneration)) {
                        assertTrue(TileEntityManager.reconcileActiveTypeIfCurrent(
                                index, "chunk", nestedGeneration,
                                nestedValue, null, true, true,
                                active, lastActive,
                                (nestedBlock, nestedChange, nestedType) ->
                                        events.add(nestedBlock + ":" + nestedChange + ":" + nestedType)));
                    }
                    assertTrue(TileEntityManager.finishIndexGeneration(
                            index, "chunk", nestedGeneration, Set.of()));
                });

        assertFalse(outerCurrent);
        assertFalse(index.containsKey("chunk"));
        assertTrue(active.get(TileEntityType.FURNACE).isEmpty());
        assertTrue(lastActive.isEmpty());
        assertEquals(List.of(
                "first:REMOVED:FURNACE",
                "second:REMOVED:FURNACE"), events);
    }

    private static Map<TileEntityType, Set<String>> emptyActiveSets() {
        Map<TileEntityType, Set<String>> active = new EnumMap<>(TileEntityType.class);
        for (TileEntityType type : TileEntityType.values()) {
            active.put(type, new HashSet<>());
        }
        return active;
    }

    private static Set<ChunkPosition> chunkWindow(World world, int centerX, int centerZ, int range) {
        Set<ChunkPosition> chunks = new LinkedHashSet<>();
        for (int z = centerZ - range; z <= centerZ + range; z++) {
            for (int x = centerX - range; x <= centerX + range; x++) {
                chunks.add(new ChunkPosition(world, x, z));
            }
        }
        return chunks;
    }

    private static World world(UUID id) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) {
                        return id;
                    }
                    if (method.getName().equals("toString")) {
                        return "World[" + id + "]";
                    }
                    throw new UnsupportedOperationException(method.toString());
                });
    }
}
