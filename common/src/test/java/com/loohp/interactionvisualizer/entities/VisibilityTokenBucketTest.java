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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void drainsImmediatelyWhenRateLimitingIsDisabled() {
        VisibilityTokenBucket<Integer> bucket = new VisibilityTokenBucket<>(0);
        bucket.request(1);
        bucket.request(2);
        bucket.request(3);
        bucket.cancel(2);

        assertEquals(List.of(1, 3), bucket.drainAll(ignored -> true));
        assertEquals(List.of(), bucket.drainAll(ignored -> true));
    }

    @Test
    void largeConfiguredLimitsDoNotOverflowTokenRefill() {
        VisibilityTokenBucket<Integer> bucket = new VisibilityTokenBucket<>(Integer.MAX_VALUE);
        bucket.request(1);

        assertEquals(List.of(1), bucket.drain(Integer.MAX_VALUE, Integer.MAX_VALUE, ignored -> true));
    }

    @Test
    void drainsIntoReusableCollectionWithoutRetainingPreviousResults() {
        VisibilityTokenBucket<Integer> bucket = new VisibilityTokenBucket<>(2);
        bucket.request(1);
        bucket.request(2);
        bucket.request(3);
        List<Integer> ready = new ArrayList<>();

        bucket.drainInto(2, 0, ignored -> true, ready);
        assertEquals(List.of(1, 2), ready);

        ready.clear();
        bucket.drainInto(2, 1, ignored -> true, ready);
        assertEquals(List.of(3), ready);

        ready.clear();
        bucket.request(4);
        bucket.request(5);
        bucket.drainAllInto(value -> value != 5, ready);
        assertEquals(List.of(4), ready);
        assertFalse(bucket.hasPending());
    }

    @Test
    void canceledEntriesRemainPendingOnlyUntilTheNextDrain() {
        VisibilityTokenBucket<Integer> bucket = new VisibilityTokenBucket<>(1);
        bucket.request(1);
        bucket.cancel(1);

        assertTrue(bucket.hasPending());
        bucket.drainAll(ignored -> true);
        assertFalse(bucket.hasPending());
    }
}
