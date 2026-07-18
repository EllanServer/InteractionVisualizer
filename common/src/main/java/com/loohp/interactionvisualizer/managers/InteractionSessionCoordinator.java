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

import com.loohp.interactionvisualizer.InteractionVisualizer;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Close/quit/world-change driven workstation cleanup with one on-demand,
 * low-frequency audit as a safety net for inventories closed without events.
 */
public final class InteractionSessionCoordinator {

    private static final long AUDIT_PERIOD_TICKS = 100L;
    private static final Map<Object, Lane> LANES = new IdentityHashMap<>();

    private static ScheduledTask auditTask;

    private InteractionSessionCoordinator() {
    }

    public static void register(Object owner, Supplier<? extends Collection<Player>> players,
                                Predicate<Player> valid, Consumer<Player> cleanup) {
        LANES.putIfAbsent(Objects.requireNonNull(owner, "owner"),
                new Lane(players, valid, cleanup));
    }

    /** Called when a lane creates or refreshes a session. */
    public static void touch() {
        if (auditTask == null || auditTask.isCancelled()) {
            auditTask = Scheduler.runTaskTimer(InteractionVisualizer.plugin,
                    InteractionSessionCoordinator::audit, AUDIT_PERIOD_TICKS, AUDIT_PERIOD_TICKS);
        }
    }

    public static void invalidate(Player player) {
        if (player == null) {
            return;
        }
        for (Lane lane : LANES.values()) {
            lane.cleanup.accept(player);
        }
        stopIfIdle();
    }

    public static int retainedLaneCount() {
        return LANES.size();
    }

    public static int retainedSessionCount() {
        int count = 0;
        for (Lane lane : LANES.values()) {
            count += lane.players.get().size();
        }
        return count;
    }

    public static void shutdown() {
        ScheduledTask task = auditTask;
        auditTask = null;
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
        for (Lane lane : LANES.values()) {
            lane.buffer.clear();
        }
        LANES.clear();
    }

    private static void audit() {
        for (Lane lane : LANES.values()) {
            lane.audit();
        }
        stopIfIdle();
    }

    private static void stopIfIdle() {
        for (Lane lane : LANES.values()) {
            if (!lane.players.get().isEmpty()) {
                return;
            }
        }
        ScheduledTask task = auditTask;
        auditTask = null;
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    private static final class Lane {

        private final Supplier<? extends Collection<Player>> players;
        private final Predicate<Player> valid;
        private final Consumer<Player> cleanup;
        private final ArrayList<Player> buffer = new ArrayList<>();

        private Lane(Supplier<? extends Collection<Player>> players,
                     Predicate<Player> valid, Consumer<Player> cleanup) {
            this.players = Objects.requireNonNull(players, "players");
            this.valid = Objects.requireNonNull(valid, "valid");
            this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        }

        private void audit() {
            buffer.clear();
            buffer.addAll(players.get());
            for (Player player : buffer) {
                if (!valid.test(player)) {
                    cleanup.accept(player);
                }
            }
            buffer.clear();
        }
    }
}
