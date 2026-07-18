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

import com.loohp.interactionvisualizer.integration.CullingBounds;
import com.loohp.interactionvisualizer.integration.ViewerCullingManager;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.plugin.user.BukkitServerPlayer;
import net.momirealms.craftengine.core.entity.culling.Cullable;
import net.momirealms.craftengine.core.entity.culling.CullingData;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.world.collision.AABB;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Adds only InteractionVisualizer's sent-chunk candidates to CraftEngine's
 * per-player tracked set. CraftEngine performs its distance/occlusion pass on
 * its own worker; callbacks are coalesced onto IV's main-thread coordinator.
 */
public final class CraftEngineViewerCullingManager implements ViewerCullingManager {

    private static final AtomicInteger NEXT_TRACKING_ID = new AtomicInteger(Integer.MIN_VALUE);

    private final Plugin plugin;
    private final VisibilityListener listener;
    private final boolean providerEnabled;
    private final CullingRegistrationIndex<Registration> registrations = new CullingRegistrationIndex<>();
    private final Map<UUID, SharedCullingState> statesByLogical = new ConcurrentHashMap<>();
    private final Map<CullingRegistrationIndex.Key, Boolean> pendingDecisions = new ConcurrentHashMap<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();

    private volatile ScheduledTask drainTask;
    private volatile boolean closed;
    private final AtomicBoolean failureReported = new AtomicBoolean();

    public CraftEngineViewerCullingManager(Plugin plugin, VisibilityListener listener) {
        this.plugin = plugin;
        this.listener = listener;
        this.providerEnabled = Config.enableEntityCulling() && Config.entityCullingRayTracing();
    }

    @Override
    public boolean enabled() {
        return providerEnabled && !closed;
    }

    @Override
    public boolean track(Player viewer, UUID logicalId, CullingBounds bounds) {
        if (!enabled() || viewer == null || !viewer.isOnline() || logicalId == null || bounds == null) {
            return false;
        }

        SharedCullingState state = statesByLogical.compute(logicalId, (ignored, current) -> {
            if (current == null) {
                return new SharedCullingState(bounds);
            }
            current.update(bounds);
            return current;
        });
        CullingRegistrationIndex.Key key = new CullingRegistrationIndex.Key(
                viewer.getUniqueId(), logicalId);
        Registration current = registrations.get(key);
        if (current != null) {
            pendingDecisions.put(key, current.visible);
            scheduleDecisionDrain();
            return true;
        }

        try {
            BukkitServerPlayer craftPlayer = BukkitAdaptor.adapt(viewer);
            if (craftPlayer == null) {
                return false;
            }
            Registration registration = new Registration(key, NEXT_TRACKING_ID.getAndIncrement(),
                    craftPlayer, state);
            Registration raced = registrations.putIfAbsent(key, registration);
            if (raced != null) {
                return true;
            }

            // Start pessimistically hidden. If CE observes visibility on its
            // next 50 ms pass, that newer true decision replaces this one.
            pendingDecisions.put(key, false);
            scheduleDecisionDrain();
            craftPlayer.addTrackedEntity(registration.trackingId, registration.cullable);
            return true;
        } catch (LinkageError | RuntimeException exception) {
            Registration registration = registrations.remove(key);
            if (registration != null) {
                detachRegistration(registration);
                removeStateIfUnused(logicalId, state);
            }
            pendingDecisions.remove(key);
            disableAfterFailure(exception);
            return false;
        }
    }

    @Override
    public void update(UUID logicalId, CullingBounds bounds) {
        if (!enabled() || logicalId == null || bounds == null) {
            return;
        }
        SharedCullingState state = statesByLogical.get(logicalId);
        if (state != null) {
            state.update(bounds);
        }
    }

    @Override
    public void untrack(UUID viewerId, UUID logicalId) {
        if (viewerId == null || logicalId == null) {
            return;
        }
        Registration registration = registrations.remove(
                new CullingRegistrationIndex.Key(viewerId, logicalId));
        if (registration != null) {
            detachRegistration(registration);
            removeStateIfUnused(logicalId, registration.state);
        }
    }

    @Override
    public void clearViewer(UUID viewerId) {
        List<Registration> removed = registrations.removeViewer(viewerId);
        for (Registration registration : removed) {
            detachRegistration(registration);
            removeStateIfUnused(registration.key.logicalId(), registration.state);
        }
    }

    @Override
    public void clearLogical(UUID logicalId) {
        for (Registration registration : registrations.removeLogical(logicalId)) {
            detachRegistration(registration);
        }
        statesByLogical.remove(logicalId);
    }

    @Override
    public void retainLogical(UUID logicalId, Set<UUID> viewerIds) {
        List<Registration> removed = registrations.retainLogical(logicalId, viewerIds);
        for (Registration registration : removed) {
            detachRegistration(registration);
        }
        if (!removed.isEmpty()) {
            removeStateIfUnused(logicalId, removed.getFirst().state);
        }
    }

    @Override
    public int retainedRegistrations() {
        return registrations.size();
    }

    private void detachRegistration(Registration registration) {
        registration.active = false;
        pendingDecisions.remove(registration.key);
        try {
            registration.player.removeTrackedEntity(registration.trackingId);
        } catch (LinkageError | RuntimeException exception) {
            if (!closed) {
                disableAfterFailure(exception);
            }
        }
    }

    private void removeStateIfUnused(UUID logicalId, SharedCullingState state) {
        if (!registrations.hasLogical(logicalId)) {
            statesByLogical.remove(logicalId, state);
        }
    }

    private void decision(Registration registration, boolean visible) {
        if (!registration.active || closed || registrations.get(registration.key) != registration) {
            return;
        }
        registration.visible = visible;
        pendingDecisions.put(registration.key, visible);
        scheduleDecisionDrain();
    }

    private void scheduleDecisionDrain() {
        if (closed || !plugin.isEnabled() || !drainScheduled.compareAndSet(false, true)) {
            return;
        }
        drainTask = Scheduler.runTask(plugin, this::drainDecisions);
    }

    private void drainDecisions() {
        try {
            Map<CullingRegistrationIndex.Key, Boolean> snapshot = new HashMap<>(pendingDecisions);
            for (Map.Entry<CullingRegistrationIndex.Key, Boolean> entry : snapshot.entrySet()) {
                Registration registration = registrations.get(entry.getKey());
                if (registration != null && registration.active
                        && pendingDecisions.remove(entry.getKey(), entry.getValue())) {
                    listener.visibilityChanged(entry.getKey().viewerId(),
                            entry.getKey().logicalId(), entry.getValue());
                }
            }
        } finally {
            drainTask = null;
            drainScheduled.set(false);
            if (!pendingDecisions.isEmpty()) {
                scheduleDecisionDrain();
            }
        }
    }

    private void disableAfterFailure(Throwable throwable) {
        if (closed) {
            return;
        }
        closed = true;
        List<Registration> snapshot = registrations.clear();
        for (Registration registration : snapshot) {
            registration.active = false;
            try {
                registration.player.removeTrackedEntity(registration.trackingId);
            } catch (Throwable suppressed) {
                throwable.addSuppressed(suppressed);
            }
        }
        statesByLogical.clear();
        pendingDecisions.clear();
        if (failureReported.compareAndSet(false, true)) {
            plugin.getLogger().log(Level.WARNING,
                    "CraftEngine display culling failed and was disabled; candidates will remain visible", throwable);
        }
        if (plugin.isEnabled()) {
            Scheduler.runTask(plugin, () -> {
                for (Registration registration : snapshot) {
                    listener.visibilityChanged(registration.key.viewerId(),
                            registration.key.logicalId(), true);
                }
            });
        }
    }

    @Override
    public void shutdown() {
        if (closed && registrations.isEmpty()) {
            return;
        }
        closed = true;
        ScheduledTask pendingDrain = drainTask;
        if (pendingDrain != null) {
            pendingDrain.cancel();
        }
        for (Registration registration : registrations.clear()) {
            registration.active = false;
            try {
                registration.player.removeTrackedEntity(registration.trackingId);
            } catch (LinkageError | RuntimeException exception) {
                if (failureReported.compareAndSet(false, true)) {
                    plugin.getLogger().log(Level.WARNING,
                            "Could not fully unregister CraftEngine culling during shutdown", exception);
                }
            }
        }
        statesByLogical.clear();
        pendingDecisions.clear();
        drainScheduled.set(false);
        drainTask = null;
    }

    private final class Registration {

        private final CullingRegistrationIndex.Key key;
        private final int trackingId;
        private final BukkitServerPlayer player;
        private final SharedCullingState state;
        private final Cullable cullable;
        private volatile boolean active = true;
        private volatile boolean visible;

        private Registration(CullingRegistrationIndex.Key key, int trackingId, BukkitServerPlayer player,
                             SharedCullingState state) {
            this.key = key;
            this.trackingId = trackingId;
            this.player = player;
            this.state = state;
            this.cullable = new Cullable() {
                @Override
                public void show(net.momirealms.craftengine.core.entity.player.Player ignored) {
                    decision(Registration.this, true);
                }

                @Override
                public void hide(net.momirealms.craftengine.core.entity.player.Player ignored) {
                    decision(Registration.this, false);
                }

                @Override
                public CullingData cullingData() {
                    return state.data;
                }
            };
        }
    }

    private static final class SharedCullingState {

        private volatile CullingBounds bounds;
        private volatile CullingData data;

        private SharedCullingState(CullingBounds bounds) {
            update(bounds);
        }

        private void update(CullingBounds updated) {
            if (updated.equals(bounds)) {
                return;
            }
            bounds = updated;
            data = new CullingData(new AABB(
                    updated.minX(), updated.minY(), updated.minZ(),
                    updated.maxX(), updated.maxY(), updated.maxZ()),
                    updated.maxDistance(), updated.expansion(), updated.rayTracing());
        }
    }
}
