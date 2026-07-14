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

package com.loohp.interactionvisualizer.blocks;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockUpdateSchedulerTest {

    @Test
    void coalescesDirtyValuesUntilTheirReadyTick() {
        BlockUpdateScheduler<String> scheduler = new BlockUpdateScheduler<>(Set::of, 20, 600);
        List<String> updates = new ArrayList<>();

        scheduler.markDirty("furnace", 11);
        scheduler.markDirty("furnace", 11);
        scheduler.markDirty("furnace", 12);

        assertEquals(0, scheduler.tick(10, 64, value -> {
            updates.add(value);
            return false;
        }));
        assertEquals(1, scheduler.pendingDirtyCount());
        assertEquals(1, scheduler.tick(11, 64, value -> {
            updates.add(value);
            return false;
        }));
        assertEquals(List.of("furnace"), updates);
        assertEquals(0, scheduler.pendingDirtyCount());
    }

    @Test
    void prioritizesUrgentDirtyAndRotatesActiveWithinTheConfiguredPeriod() {
        BlockUpdateScheduler<String> scheduler = new BlockUpdateScheduler<>(Set::of, 2, 600);
        List<String> updates = new ArrayList<>();
        scheduler.markActive("a", 1);
        scheduler.markActive("b", 1);
        scheduler.markActive("c", 1);
        scheduler.markDirty("c", 1);

        assertEquals(3, scheduler.tick(1, 64, value -> {
            updates.add(value);
            return true;
        }));
        assertEquals("c", updates.getFirst());
        assertEquals(3, scheduler.activeCount());

        updates.clear();
        assertEquals(0, scheduler.tick(2, 64, value -> {
            updates.add(value);
            return true;
        }));
        assertEquals(3, scheduler.activeCount());

        // Values are not due again until two ticks after their prior update.
        assertEquals(2, scheduler.tick(3, 64, value -> {
            updates.add(value);
            return !value.equals("a");
        }));
        assertEquals(2, scheduler.activeCount());
    }

    @Test
    void synchronizedLevelSignalsDoNotBurstPastTheActiveCadence() {
        BlockUpdateScheduler<Integer> scheduler = new BlockUpdateScheduler<>(Set::of, 20, 600);
        for (int value = 0; value < 100; value++) {
            scheduler.markActive(value, 1);
        }
        for (int value = 0; value < 100; value++) {
            scheduler.markDirtyUnlessActive(value, 1);
        }

        assertEquals(0, scheduler.pendingDirtyCount());
        int total = 0;
        for (int tick = 1; tick <= 20; tick++) {
            int checks = scheduler.tick(tick, 64, value -> true);
            assertEquals(5, checks);
            total += checks;
        }
        assertEquals(100, total);
        assertEquals(100, scheduler.activeCount());
    }

    @Test
    void levelSignalsStillBootstrapInactiveValues() {
        BlockUpdateScheduler<String> scheduler = new BlockUpdateScheduler<>(Set::of, 20, 600);
        scheduler.markDirtyUnlessActive("inactive", 2);

        assertEquals(1, scheduler.pendingDirtyCount());
        assertEquals(0, scheduler.tick(1, 64, value -> true));
        assertEquals(1, scheduler.tick(2, 64, value -> false));
        assertEquals(0, scheduler.pendingDirtyCount());
    }

    @Test
    void coalescedStopSignalLeavesTheActivePeriodAsTheMaximumDelay() {
        BlockUpdateScheduler<String> scheduler = new BlockUpdateScheduler<>(Set::of, 20, 600);
        scheduler.markActive("furnace", 1);
        assertEquals(1, scheduler.tick(1, 64, value -> true));

        scheduler.markDirtyUnlessActive("furnace", 2);
        assertEquals(0, scheduler.pendingDirtyCount());
        for (int tick = 2; tick < 21; tick++) {
            assertEquals(0, scheduler.tick(tick, 64, value -> false));
        }
        assertEquals(1, scheduler.tick(21, 64, value -> false));
        assertEquals(0, scheduler.activeCount());
    }

    @Test
    void coalescedSignalDoesNotDiscardAnExistingUrgentInvalidation() {
        BlockUpdateScheduler<String> scheduler = new BlockUpdateScheduler<>(Set::of, 20, 600);
        scheduler.markActive("furnace", 10);
        scheduler.markDirty("furnace", 2);
        scheduler.markDirtyUnlessActive("furnace", 2);

        assertEquals(1, scheduler.pendingDirtyCount());
        assertEquals(1, scheduler.tick(2, 64, value -> true));
        assertEquals(0, scheduler.pendingDirtyCount());
        assertEquals(1, scheduler.activeCount());
    }

    @Test
    void initialAndPeriodicAuditsAreBoundedWithoutSnapshots() {
        Set<Integer> source = new LinkedHashSet<>();
        for (int i = 0; i < 10; i++) {
            source.add(i);
        }
        BlockUpdateScheduler<Integer> scheduler = new BlockUpdateScheduler<>(() -> source, 20, 5);

        assertEquals(3, scheduler.tick(0, 3, value -> false));
        assertEquals(3, scheduler.tick(1, 3, value -> false));
        assertEquals(3, scheduler.tick(2, 3, value -> false));
        assertEquals(1, scheduler.tick(3, 3, value -> false));
        assertEquals(0, scheduler.tick(4, 3, value -> false));

        // The next audit begins at tick 5 and uses ceil(10 / 5) = 2 checks.
        assertEquals(2, scheduler.tick(5, 3, value -> false));
    }

    @Test
    void removalCancelsDirtyAndActiveWork() {
        BlockUpdateScheduler<String> scheduler = new BlockUpdateScheduler<>(Set::of, 1, 600);
        scheduler.markDirty("gone", 1);
        scheduler.markActive("gone", 1);
        scheduler.remove("gone");

        assertEquals(0, scheduler.tick(1, 64, value -> true));
        assertEquals(0, scheduler.pendingDirtyCount());
        assertEquals(0, scheduler.activeCount());
        assertEquals(0, scheduler.invalidatedAuditValueCount());
    }

    @Test
    void removalInvalidatesValuesAlreadyCapturedByAnAuditIterator() {
        Set<String> source = new LinkedHashSet<>(List.of("kept", "gone"));
        BlockUpdateScheduler<String> scheduler = new BlockUpdateScheduler<>(
                () -> new ArrayList<>(source), 20, 600);
        List<String> updates = new ArrayList<>();

        assertEquals(1, scheduler.tick(0, 1, value -> {
            updates.add(value);
            return false;
        }));
        source.remove("gone");
        scheduler.remove("gone");

        assertEquals(0, scheduler.tick(1, 1, value -> {
            updates.add(value);
            return false;
        }));
        assertEquals(List.of("kept"), updates);
    }

    @Test
    void shrinkingActiveSetDoesNotReduceTheCurrentRotationBudget() {
        BlockUpdateScheduler<Integer> scheduler = new BlockUpdateScheduler<>(Set::of, 3, 600);
        for (int value = 0; value < 6; value++) {
            scheduler.markActive(value, 1);
        }

        assertEquals(2, scheduler.tick(1, 64, value -> false));
        assertEquals(2, scheduler.tick(2, 64, value -> false));
        assertEquals(2, scheduler.tick(3, 64, value -> false));
        assertEquals(0, scheduler.activeCount());
    }

}
