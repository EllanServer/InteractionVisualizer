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

import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.EntityHolderTestFactory;
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.entityholders.VisualizerEntity;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayManagerShutdownLifecycleTest {

    @Test
    void shutdownIncludesRepresentationsThatAlreadyLeftActive() throws ReflectiveOperationException {
        DisplayEntity active = EntityHolderTestFactory.allocate(DisplayEntity.class);
        DisplayEntity detachedVirtualText = EntityHolderTestFactory.allocate(DisplayEntity.class);
        Item detachedVirtualItem = EntityHolderTestFactory.allocate(Item.class);
        DisplayEntity detachedActualEntity = EntityHolderTestFactory.allocate(DisplayEntity.class);

        Set<VisualizerEntity> candidates = DisplayManager.shutdownCleanupCandidates(
                Set.of(active),
                Set.of(),
                Set.of(detachedVirtualText),
                Set.of(detachedVirtualItem),
                Set.of(detachedActualEntity));

        assertEquals(4, candidates.size());
        assertTrue(candidates.containsAll(Set.of(
                active, detachedVirtualText, detachedVirtualItem, detachedActualEntity)));
    }

}
