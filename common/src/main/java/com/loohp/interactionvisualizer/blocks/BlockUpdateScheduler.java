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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Main-thread scheduler for event-driven block displays.
 *
 * <p>Dirty entries are coalesced by key and become eligible on an explicit
 * tick. Active entries are rotated through a reusable queue so each entry is
 * checked approximately once per configured update period. Callers can absorb
 * synchronized level signals for already-active entries into that cadence
 * without weakening urgent interaction edges. A weakly
 * consistent iterator performs a bounded safety audit without allocating a
 * snapshot of every tracked block. The dirty cap supplied to {@link #tick}
 * applies to this scheduler instance (one display type), not globally across
 * every display.</p>
 */
final class BlockUpdateScheduler<T> {

    @FunctionalInterface
    interface Updater<T> {
        /**
         * @return {@code true} while the value needs periodic active updates
         */
        boolean update(T value);
    }

    private final Supplier<? extends Collection<T>> auditSource;
    private final int activePeriod;
    private final int auditPeriod;
    private final NavigableMap<Long, LinkedHashSet<T>> dirtyByTick;
    private final Map<T, Long> dirtyDueTick;
    private final NavigableMap<Long, LinkedHashSet<T>> activeByTick;
    private final Map<T, Long> activeDueTick;
    private final HashSet<T> processedThisTick;
    private final HashSet<T> removedValues;

    private Iterator<T> auditIterator;
    private long nextAuditTick;
    private int auditBudget;
    private int activeBudget;
    private boolean initialAudit;

    BlockUpdateScheduler(Supplier<? extends Collection<T>> auditSource, int activePeriod, int auditPeriod) {
        this.auditSource = Objects.requireNonNull(auditSource, "auditSource");
        this.activePeriod = Math.max(1, activePeriod);
        this.auditPeriod = Math.max(1, auditPeriod);
        this.dirtyByTick = new TreeMap<>();
        this.dirtyDueTick = new HashMap<>();
        this.activeByTick = new TreeMap<>();
        this.activeDueTick = new HashMap<>();
        this.processedThisTick = new HashSet<>();
        this.removedValues = new HashSet<>();
        this.nextAuditTick = Long.MIN_VALUE;
        this.activeBudget = 0;
        this.initialAudit = true;
    }

    void markDirty(T value, long readyTick) {
        if (value == null) {
            return;
        }
        this.removedValues.remove(value);
        Long previousTick = this.dirtyDueTick.get(value);
        if (previousTick != null && previousTick <= readyTick) {
            return;
        }
        if (previousTick != null) {
            LinkedHashSet<T> previous = this.dirtyByTick.get(previousTick);
            if (previous != null) {
                previous.remove(value);
                if (previous.isEmpty()) {
                    this.dirtyByTick.remove(previousTick);
                }
            }
        }
        this.dirtyDueTick.put(value, readyTick);
        this.dirtyByTick.computeIfAbsent(readyTick, ignored -> new LinkedHashSet<>()).add(value);
    }

    /**
     * Coalesces a non-urgent level signal into the existing active cadence.
     * Inactive values still enter the dirty queue so the signal can bootstrap
     * tracking. Urgent player, inventory and lifecycle edges must use
     * {@link #markDirty(Object, long)}.
     */
    void markDirtyUnlessActive(T value, long readyTick) {
        if (value != null && this.activeDueTick.containsKey(value)) {
            return;
        }
        this.markDirty(value, readyTick);
    }

    void markActive(T value, long readyTick) {
        this.removedValues.remove(value);
        this.schedule(value, readyTick, this.activeDueTick, this.activeByTick);
    }

    void remove(T value) {
        if (value == null) {
            return;
        }
        if (this.auditIterator != null) {
            this.removedValues.add(value);
        }
        this.unschedule(value, this.activeDueTick, this.activeByTick);
        this.unschedule(value, this.dirtyDueTick, this.dirtyByTick);
    }

    int tick(long tick, int maxDirtyPerTick, Updater<T> updater) {
        Objects.requireNonNull(updater, "updater");
        int dirtyBudget = Math.max(1, maxDirtyPerTick);
        this.processedThisTick.clear();

        int checks = this.drainDirty(tick, dirtyBudget, updater);
        checks += this.drainActive(tick, updater);
        checks += this.drainAudit(tick, dirtyBudget, updater);
        return checks;
    }

    private int drainDirty(long tick, int budget, Updater<T> updater) {
        int checks = 0;
        while (checks < budget) {
            Map.Entry<Long, LinkedHashSet<T>> entry = this.dirtyByTick.firstEntry();
            if (entry == null || entry.getKey() > tick) {
                break;
            }
            Iterator<T> iterator = entry.getValue().iterator();
            if (!iterator.hasNext()) {
                this.dirtyByTick.pollFirstEntry();
                continue;
            }
            T value = iterator.next();
            iterator.remove();
            this.dirtyDueTick.remove(value, entry.getKey());
            if (entry.getValue().isEmpty()) {
                this.dirtyByTick.pollFirstEntry();
            }
            if (this.updateOnce(value, tick, updater)) {
                checks++;
            }
        }
        return checks;
    }

    private int drainActive(long tick, Updater<T> updater) {
        if (this.activeDueTick.isEmpty()) {
            this.activeBudget = 0;
            return 0;
        }
        this.activeBudget = Math.max(this.activeBudget,
                Math.max(1, (this.activeDueTick.size() + this.activePeriod - 1) / this.activePeriod));
        int checks = 0;
        while (checks < this.activeBudget) {
            Map.Entry<Long, LinkedHashSet<T>> entry = this.activeByTick.firstEntry();
            if (entry == null || entry.getKey() > tick) {
                break;
            }
            Iterator<T> iterator = entry.getValue().iterator();
            if (!iterator.hasNext()) {
                this.activeByTick.pollFirstEntry();
                continue;
            }
            T value = iterator.next();
            iterator.remove();
            this.activeDueTick.remove(value, entry.getKey());
            if (entry.getValue().isEmpty()) {
                this.activeByTick.pollFirstEntry();
            }
            if (this.removedValues.contains(value)) {
                continue;
            }
            if (this.processedThisTick.contains(value)) {
                this.markActive(value, tick + this.activePeriod);
                continue;
            }
            boolean keepActive = this.updateAndRemember(value, updater);
            checks++;
            if (keepActive) {
                this.markActive(value, tick + this.activePeriod);
            }
        }
        return checks;
    }

    private int drainAudit(long tick, int initialBudget, Updater<T> updater) {
        if (this.auditIterator == null && tick >= this.nextAuditTick) {
            Collection<T> source = this.auditSource.get();
            this.auditIterator = source.iterator();
            this.auditBudget = this.initialAudit
                    ? initialBudget
                    : Math.max(1, (source.size() + this.auditPeriod - 1) / this.auditPeriod);
            this.initialAudit = false;
            this.nextAuditTick = tick + this.auditPeriod;
        }
        if (this.auditIterator == null) {
            return 0;
        }

        int checks = 0;
        int attempts = this.auditBudget;
        while (attempts-- > 0 && this.auditIterator.hasNext()) {
            T value = this.auditIterator.next();
            if (this.updateOnce(value, tick, updater)) {
                checks++;
            }
        }
        if (!this.auditIterator.hasNext()) {
            this.auditIterator = null;
            this.removedValues.clear();
        }
        return checks;
    }

    private boolean updateOnce(T value, long tick, Updater<T> updater) {
        if (this.removedValues.contains(value)) {
            return false;
        }
        if (!this.processedThisTick.add(value)) {
            return false;
        }
        boolean keepActive = updater.update(value);
        if (keepActive) {
            this.unschedule(value, this.activeDueTick, this.activeByTick);
            this.markActive(value, tick + this.activePeriod);
        } else {
            this.unschedule(value, this.activeDueTick, this.activeByTick);
        }
        return true;
    }

    private boolean updateAndRemember(T value, Updater<T> updater) {
        this.processedThisTick.add(value);
        return updater.update(value);
    }

    private void schedule(T value, long readyTick, Map<T, Long> dueTicks,
                          NavigableMap<Long, LinkedHashSet<T>> byTick) {
        if (value == null) {
            return;
        }
        Long previousTick = dueTicks.get(value);
        if (previousTick != null && previousTick <= readyTick) {
            return;
        }
        if (previousTick != null) {
            LinkedHashSet<T> previous = byTick.get(previousTick);
            if (previous != null) {
                previous.remove(value);
                if (previous.isEmpty()) {
                    byTick.remove(previousTick);
                }
            }
        }
        dueTicks.put(value, readyTick);
        byTick.computeIfAbsent(readyTick, ignored -> new LinkedHashSet<>()).add(value);
    }

    private void unschedule(T value, Map<T, Long> dueTicks,
                            NavigableMap<Long, LinkedHashSet<T>> byTick) {
        Long dueTick = dueTicks.remove(value);
        if (dueTick == null) {
            return;
        }
        LinkedHashSet<T> values = byTick.get(dueTick);
        if (values != null) {
            values.remove(value);
            if (values.isEmpty()) {
                byTick.remove(dueTick);
            }
        }
    }

    int pendingDirtyCount() {
        return this.dirtyDueTick.size();
    }

    int activeCount() {
        return this.activeDueTick.size();
    }

    int invalidatedAuditValueCount() {
        return this.removedValues.size();
    }

}
