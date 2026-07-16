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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Deduplicated token bucket used to smooth client visibility bursts. */
final class VisibilityTokenBucket<T> {

    private final Deque<T> pending = new ArrayDeque<>();
    private final Set<T> queued = new HashSet<>();
    private int tokens;

    VisibilityTokenBucket(int initialTokens) {
        this.tokens = Math.max(0, initialTokens);
    }

    void request(T value) {
        if (queued.add(value)) {
            pending.addLast(value);
        }
    }

    void cancel(T value) {
        queued.remove(value);
    }

    List<T> drain(int capacity, int refill, Predicate<T> stillWanted) {
        List<T> ready = new ArrayList<>();
        drainInto(capacity, refill, stillWanted, ready);
        return ready;
    }

    void drainInto(int capacity, int refill, Predicate<T> stillWanted,
                   Collection<? super T> ready) {
        int safeCapacity = Math.max(1, capacity);
        long replenished = (long) tokens + Math.max(0, refill);
        tokens = (int) Math.min(safeCapacity, replenished);
        if (tokens == 0 || pending.isEmpty()) {
            return;
        }

        while (tokens > 0 && !pending.isEmpty()) {
            T value = pending.removeFirst();
            if (!queued.remove(value) || !stillWanted.test(value)) {
                continue;
            }
            ready.add(value);
            tokens--;
        }
    }

    List<T> drainAll(Predicate<T> stillWanted) {
        List<T> ready = new ArrayList<>();
        drainAllInto(stillWanted, ready);
        return ready;
    }

    void drainAllInto(Predicate<T> stillWanted, Collection<? super T> ready) {
        while (!pending.isEmpty()) {
            T value = pending.removeFirst();
            if (queued.remove(value) && stillWanted.test(value)) {
                ready.add(value);
            }
        }
    }

    void clear() {
        pending.clear();
        queued.clear();
    }

    boolean hasPending() {
        return !pending.isEmpty();
    }
}
