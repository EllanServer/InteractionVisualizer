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

package com.loohp.interactionvisualizer.integration.craftengine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CullingRegistrationIndexTest {

    @Test
    void reverseIndexesRemoveOnlyTheRequestedViewerAndLogical() {
        CullingRegistrationIndex<String> index = new CullingRegistrationIndex<>();
        UUID viewerA = UUID.randomUUID();
        UUID viewerB = UUID.randomUUID();
        UUID logicalA = UUID.randomUUID();
        UUID logicalB = UUID.randomUUID();
        CullingRegistrationIndex.Key aA = new CullingRegistrationIndex.Key(viewerA, logicalA);
        CullingRegistrationIndex.Key bA = new CullingRegistrationIndex.Key(viewerB, logicalA);
        CullingRegistrationIndex.Key aB = new CullingRegistrationIndex.Key(viewerA, logicalB);

        assertNull(index.putIfAbsent(aA, "a/a"));
        assertNull(index.putIfAbsent(bA, "b/a"));
        assertNull(index.putIfAbsent(aB, "a/b"));
        assertEquals("a/a", index.putIfAbsent(aA, "duplicate"));
        assertEquals(3, index.size());

        assertEquals(List.of("a/a"), index.retainLogical(logicalA, Set.of(viewerB)));
        assertEquals("b/a", index.get(bA));
        assertTrue(index.hasLogical(logicalB));
        assertEquals("a/b", index.get(aB));

        assertEquals(List.of("a/b"), index.removeViewer(viewerA));
        assertFalse(index.hasLogical(logicalB));
        assertEquals(List.of("b/a"), index.removeLogical(logicalA));
        assertTrue(index.isEmpty());
    }

    @Test
    void clearReturnsEveryRetainedHandleAndEmptiesAllIndexes() {
        CullingRegistrationIndex<Integer> index = new CullingRegistrationIndex<>();
        UUID logical = UUID.randomUUID();
        index.putIfAbsent(new CullingRegistrationIndex.Key(UUID.randomUUID(), logical), 1);
        index.putIfAbsent(new CullingRegistrationIndex.Key(UUID.randomUUID(), logical), 2);

        assertEquals(Set.of(1, 2), Set.copyOf(index.clear()));
        assertEquals(0, index.size());
        assertFalse(index.hasLogical(logical));
        assertTrue(index.clear().isEmpty());
    }
}
