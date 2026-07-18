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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Atomic forward/reverse bookkeeping for optional per-viewer culling handles.
 * Provider callbacks only perform lock-free key lookups; lifecycle mutations
 * are rare and serialized so quit/remove races cannot retain one-sided state.
 */
final class CullingRegistrationIndex<T> {

    private final Map<Key, T> registrations = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Key>> registrationsByViewer = new HashMap<>();
    private final Map<UUID, Set<Key>> registrationsByLogical = new HashMap<>();

    T get(Key key) {
        return registrations.get(key);
    }

    synchronized T putIfAbsent(Key key, T registration) {
        T current = registrations.putIfAbsent(key, registration);
        if (current != null) {
            return current;
        }
        registrationsByViewer.computeIfAbsent(key.viewerId(), ignored -> new HashSet<>()).add(key);
        registrationsByLogical.computeIfAbsent(key.logicalId(), ignored -> new HashSet<>()).add(key);
        return null;
    }

    synchronized T remove(Key key) {
        return removeLocked(key);
    }

    synchronized List<T> removeViewer(UUID viewerId) {
        Set<Key> keys = registrationsByViewer.get(viewerId);
        return removeKeysLocked(keys == null ? Set.of() : Set.copyOf(keys));
    }

    synchronized List<T> removeLogical(UUID logicalId) {
        Set<Key> keys = registrationsByLogical.get(logicalId);
        return removeKeysLocked(keys == null ? Set.of() : Set.copyOf(keys));
    }

    synchronized List<T> retainLogical(UUID logicalId, Set<UUID> viewerIds) {
        Set<Key> keys = registrationsByLogical.get(logicalId);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<Key> removedKeys = new ArrayList<>();
        for (Key key : keys) {
            if (!viewerIds.contains(key.viewerId())) {
                removedKeys.add(key);
            }
        }
        return removeKeysLocked(removedKeys);
    }

    synchronized boolean hasLogical(UUID logicalId) {
        Set<Key> keys = registrationsByLogical.get(logicalId);
        return keys != null && !keys.isEmpty();
    }

    int size() {
        return registrations.size();
    }

    boolean isEmpty() {
        return registrations.isEmpty();
    }

    synchronized List<T> clear() {
        List<T> removed = List.copyOf(registrations.values());
        registrations.clear();
        registrationsByViewer.clear();
        registrationsByLogical.clear();
        return removed;
    }

    private List<T> removeKeysLocked(Iterable<Key> keys) {
        List<T> removed = new ArrayList<>();
        for (Key key : keys) {
            T registration = removeLocked(key);
            if (registration != null) {
                removed.add(registration);
            }
        }
        return removed;
    }

    private T removeLocked(Key key) {
        T removed = registrations.remove(key);
        if (removed == null) {
            return null;
        }
        removeReverseKey(registrationsByViewer, key.viewerId(), key);
        removeReverseKey(registrationsByLogical, key.logicalId(), key);
        return removed;
    }

    private static <K> void removeReverseKey(Map<K, Set<Key>> index, K owner, Key key) {
        Set<Key> keys = index.get(owner);
        if (keys != null) {
            keys.remove(key);
            if (keys.isEmpty()) {
                index.remove(owner);
            }
        }
    }

    record Key(UUID viewerId, UUID logicalId) {
    }
}
