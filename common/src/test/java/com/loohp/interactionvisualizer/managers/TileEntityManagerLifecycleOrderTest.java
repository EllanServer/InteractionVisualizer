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

import com.loohp.interactionvisualizer.objectholders.TileEntity.TileEntityType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static Map<TileEntityType, Set<String>> emptyActiveSets() {
        Map<TileEntityType, Set<String>> active = new EnumMap<>(TileEntityType.class);
        for (TileEntityType type : TileEntityType.values()) {
            active.put(type, new HashSet<>());
        }
        return active;
    }
}
