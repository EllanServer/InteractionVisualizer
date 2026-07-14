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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayManagerVisibilityShowQueueTest {

    @Test
    void deduplicatesAndCancelsPendingShows() {
        DisplayManager.VisibilityShowQueue<String> queue =
                new DisplayManager.VisibilityShowQueue<>(2);

        assertTrue(queue.request("first"));
        assertFalse(queue.request("first"));
        assertTrue(queue.request("second"));
        queue.cancel("first");

        assertEquals(List.of("second"), queue.drain(2, 0, ignored -> true));
        assertTrue(queue.isEmpty());
    }

    @Test
    void cancelThenRequestUsesTheNewQueuePositionExactlyOnce() {
        DisplayManager.VisibilityShowQueue<String> queue =
                new DisplayManager.VisibilityShowQueue<>(3);
        queue.request("first");
        queue.request("second");
        queue.cancel("first");
        assertTrue(queue.request("first"));

        assertEquals(List.of("second", "first"),
                queue.drain(3, 0, ignored -> true));
        assertTrue(queue.isEmpty());
    }

    @Test
    void enforcesCapacityAndRefillsPerDrain() {
        DisplayManager.VisibilityShowQueue<Integer> queue =
                new DisplayManager.VisibilityShowQueue<>(2);
        queue.request(1);
        queue.request(2);
        queue.request(3);
        queue.request(4);

        assertEquals(List.of(1, 2), queue.drain(2, 0, ignored -> true));
        assertEquals(List.of(3), queue.drain(2, 1, ignored -> true));
        assertEquals(List.of(4), queue.drain(2, 1, ignored -> true));
        assertTrue(queue.isEmpty());
    }

    @Test
    void staleEntriesDoNotConsumeTokens() {
        DisplayManager.VisibilityShowQueue<String> queue =
                new DisplayManager.VisibilityShowQueue<>(1);
        queue.request("stale");
        queue.request("desired");

        assertEquals(List.of(), queue.drain(1, 0, "desired"::equals));
        assertEquals(List.of("desired"),
                queue.drain(1, 0, "desired"::equals));
        assertTrue(queue.isEmpty());
    }

    @Test
    void rejectedInspectionWorkIsBoundedAcrossTicks() {
        DisplayManager.VisibilityShowQueue<String> queue =
                new DisplayManager.VisibilityShowQueue<>(1, 0L);
        for (int index = 0; index < 20; index++) {
            queue.request("rejected-" + index);
        }
        queue.request("desired");

        for (long tick = 1L; tick <= 5L; tick++) {
            assertEquals(List.of(), queue.drain(4, 0, tick, "desired"::equals));
            assertFalse(queue.isEmpty());
        }
        assertEquals(List.of("desired"), queue.drain(4, 0, 6L, "desired"::equals));
        assertTrue(queue.isEmpty());
    }

    @Test
    void cancelledEntriesDoNotLeaveInspectionBacklog() {
        DisplayManager.VisibilityShowQueue<String> queue =
                new DisplayManager.VisibilityShowQueue<>(1, 0L);
        for (int index = 0; index < 20; index++) {
            queue.request("cancelled-" + index);
        }
        queue.request("desired");
        for (int index = 0; index < 20; index++) {
            queue.cancel("cancelled-" + index);
        }

        assertEquals(List.of("desired"), queue.drain(4, 0, 1L, ignored -> true));
        assertTrue(queue.isEmpty());
    }

    @Test
    void anEmptyQueueKeepsItsSpentBucketForTheNextWave() {
        DisplayManager.VisibilityShowQueue<Integer> queue =
                new DisplayManager.VisibilityShowQueue<>(2, 0L);
        queue.request(1);
        queue.request(2);

        assertEquals(List.of(1, 2), queue.drain(2, 1, 1L, ignored -> true));
        assertTrue(queue.isEmpty());

        queue.request(3);
        queue.request(4);
        assertEquals(List.of(3), queue.drain(2, 1, 2L, ignored -> true));
        assertEquals(List.of(4), queue.drain(2, 1, 3L, ignored -> true));
    }

    @Test
    void disablingLimitCanDrainEverythingStillDesired() {
        DisplayManager.VisibilityShowQueue<String> queue =
                new DisplayManager.VisibilityShowQueue<>(0);
        queue.request("first");
        queue.request("stale");
        queue.request("second");

        assertEquals(List.of("first", "second"),
                queue.drainAll(value -> !value.equals("stale")));
        assertTrue(queue.isEmpty());
    }

    @Test
    void callbackDrainConsumesReadyEntriesWithoutBuildingAnIntermediateList() {
        DisplayManager.VisibilityShowQueue<String> queue =
                new DisplayManager.VisibilityShowQueue<>(2, 0L);
        queue.request("first");
        queue.request("stale");
        queue.request("second");
        List<String> shown = new ArrayList<>();

        int drained = queue.drainTo(2, 0, 1L, value -> {
            if (value.equals("stale")) {
                return false;
            }
            shown.add(value);
            return true;
        });

        assertEquals(1, drained);
        assertEquals(List.of("first"), shown);
        assertEquals(List.of("second"), queue.drain(2, 0, 2L, ignored -> true));
    }
}
