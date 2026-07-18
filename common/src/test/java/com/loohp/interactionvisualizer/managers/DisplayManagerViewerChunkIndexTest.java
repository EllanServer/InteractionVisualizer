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

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayManagerViewerChunkIndexTest {

    @Test
    void maintainsBothDirectionsWithoutDuplicates() {
        DisplayManager.ViewerChunkIndex<String, String> index =
                new DisplayManager.ViewerChunkIndex<>();

        assertTrue(index.add("alice", "world:0:0"));
        assertFalse(index.add("alice", "world:0:0"));
        assertTrue(index.add("bob", "world:0:0"));
        assertTrue(index.add("alice", "world:1:0"));

        assertEquals(Set.of("world:0:0", "world:1:0"), index.chunks("alice"));
        assertEquals(Set.of("alice", "bob"), index.viewers("world:0:0"));
        assertTrue(index.contains("alice", "world:1:0"));
    }

    @Test
    void viewerRemovalTouchesOnlyThatViewersChunks() {
        DisplayManager.ViewerChunkIndex<String, String> index =
                new DisplayManager.ViewerChunkIndex<>();
        index.add("alice", "world:0:0");
        index.add("alice", "world:1:0");
        index.add("bob", "world:0:0");

        index.removeViewer("alice");

        assertEquals(Set.of(), index.chunks("alice"));
        assertEquals(Set.of("bob"), index.viewers("world:0:0"));
        assertEquals(Set.of(), index.viewers("world:1:0"));
        assertTrue(index.contains("bob", "world:0:0"));
    }

    @Test
    void chunkRemovalCleansEmptyReverseBuckets() {
        DisplayManager.ViewerChunkIndex<String, String> index =
                new DisplayManager.ViewerChunkIndex<>();
        index.add("alice", "world:0:0");

        assertTrue(index.remove("alice", "world:0:0"));
        assertFalse(index.remove("alice", "world:0:0"));
        assertEquals(Set.of(), index.chunks("alice"));
        assertEquals(Set.of(), index.viewers("world:0:0"));
    }
}
