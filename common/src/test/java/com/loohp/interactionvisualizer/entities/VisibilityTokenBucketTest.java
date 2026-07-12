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

package com.loohp.interactionvisualizer.entities;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VisibilityTokenBucketTest {

    @Test
    void rateLimitsAndRefillsVisibilityChanges() {
        VisibilityTokenBucket<Integer> bucket = new VisibilityTokenBucket<>(2);
        bucket.request(1);
        bucket.request(2);
        bucket.request(3);
        bucket.request(4);

        assertEquals(List.of(1, 2), bucket.drain(2, 0, ignored -> true));
        assertEquals(List.of(3), bucket.drain(2, 1, ignored -> true));
        assertEquals(List.of(4), bucket.drain(2, 1, ignored -> true));
    }

    @Test
    void deduplicatesAndDropsCancelledOrStaleRequests() {
        VisibilityTokenBucket<Integer> bucket = new VisibilityTokenBucket<>(4);
        bucket.request(1);
        bucket.request(1);
        bucket.request(2);
        bucket.request(3);
        bucket.cancel(2);

        assertEquals(List.of(1), bucket.drain(4, 0, value -> value != 3));
    }
}
