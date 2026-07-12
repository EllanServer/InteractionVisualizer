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
        tokens = Math.min(Math.max(1, capacity), tokens + Math.max(0, refill));
        if (tokens == 0 || pending.isEmpty()) {
            return List.of();
        }

        List<T> ready = new ArrayList<>(Math.min(tokens, pending.size()));
        while (tokens > 0 && !pending.isEmpty()) {
            T value = pending.removeFirst();
            if (!queued.remove(value) || !stillWanted.test(value)) {
                continue;
            }
            ready.add(value);
            tokens--;
        }
        return ready;
    }

    void clear() {
        pending.clear();
        queued.clear();
    }
}
