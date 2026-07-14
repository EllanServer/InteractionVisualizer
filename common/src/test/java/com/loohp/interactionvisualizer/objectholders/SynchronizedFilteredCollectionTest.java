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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynchronizedFilteredCollectionTest {

    @Test
    void anyMatchHonorsNestedFiltersWithoutChangingTheLiveView() {
        List<Integer> backing = new ArrayList<>(List.of(1, 2, 3, 4));
        SynchronizedFilteredCollection<Integer> greaterThanOne =
                SynchronizedFilteredCollection.filter(backing, value -> value > 1);
        SynchronizedFilteredCollection<Integer> even =
                SynchronizedFilteredCollection.filter(greaterThanOne, value -> value % 2 == 0);

        assertTrue(even.anyMatch(value -> value == 4));
        assertFalse(even.anyMatch(value -> value == 3));

        backing.add(6);
        assertTrue(even.anyMatch(value -> value == 6));

        Collection<Integer> readOnly = SynchronizedFilteredCollection.unmodifiableCollection(even);
        assertTrue(SynchronizedFilteredCollection.anyMatch(readOnly, value -> value == 6));
        assertFalse(SynchronizedFilteredCollection.anyMatch(readOnly, value -> value == 3));
        assertThrows(UnsupportedOperationException.class, () -> readOnly.add(8));
        assertThrows(UnsupportedOperationException.class, () -> readOnly.remove(999));
        assertThrows(UnsupportedOperationException.class, () -> readOnly.removeIf(value -> false));
        assertThrows(UnsupportedOperationException.class, readOnly::clear);
    }
}
