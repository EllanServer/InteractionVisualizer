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
import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI.Modules;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreferenceManagerSessionGateTest {

    @Test
    void quitRejectsAJoinLoadThatFinishesLate() {
        PreferenceManager.PlayerSessionGate gate = new PreferenceManager.PlayerSessionGate();
        AtomicBoolean valid = new AtomicBoolean(true);
        UUID uuid = UUID.randomUUID();
        Object session = gate.open(uuid, valid);
        AtomicInteger publishes = new AtomicInteger();

        gate.end(uuid);

        assertFalse(gate.publishIfCurrent(uuid, session, valid, publishes::incrementAndGet));
        assertEquals(0, publishes.get());
    }

    @Test
    void reconnectRejectsThePreviousJoinSession() {
        PreferenceManager.PlayerSessionGate gate = new PreferenceManager.PlayerSessionGate();
        AtomicBoolean valid = new AtomicBoolean(true);
        UUID uuid = UUID.randomUUID();
        Object first = gate.open(uuid, valid);
        Object second = gate.open(uuid, valid);
        AtomicInteger publishes = new AtomicInteger();

        assertFalse(gate.publishIfCurrent(uuid, first, valid, publishes::incrementAndGet));
        assertTrue(gate.publishIfCurrent(uuid, second, valid, publishes::incrementAndGet));
        assertEquals(1, publishes.get());
    }

    @Test
    void closeInvalidatesAllSessionsBeforeLateLoadsCanPublish() {
        PreferenceManager.PlayerSessionGate gate = new PreferenceManager.PlayerSessionGate();
        AtomicBoolean valid = new AtomicBoolean(true);
        UUID uuid = UUID.randomUUID();
        Object session = gate.open(uuid, valid);
        assertNotNull(session);

        assertTrue(gate.close(valid));
        assertFalse(valid.get());
        assertFalse(gate.publishIfCurrent(uuid, session, valid, () -> { }));
        assertFalse(gate.publishIfValid(valid, () -> { }));
        assertFalse(gate.close(valid));
    }

    @Test
    void quitSaveFinishesBeforeTheSamePlayersReconnectLoad() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ExecutorService synchronousCaller = Executors.newSingleThreadExecutor();
        CountDownLatch saveStarted = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        CountDownLatch loadCallStarted = new CountDownLatch(1);
        CountDownLatch loadStarted = new CountDownLatch(1);
        try {
            PreferenceManager.PlayerIoQueue queue = new PreferenceManager.PlayerIoQueue(workers);
            UUID uuid = UUID.randomUUID();
            List<String> order = Collections.synchronizedList(new ArrayList<>());

            CompletableFuture<Void> save = queue.submit(uuid, () -> {
                order.add("save-start");
                saveStarted.countDown();
                await(releaseSave);
                order.add("save-end");
            });
            CompletableFuture<Boolean> load = CompletableFuture.supplyAsync(() -> {
                loadCallStarted.countDown();
                return queue.runAndWait(uuid, () -> {
                    order.add("load");
                    loadStarted.countDown();
                });
            }, synchronousCaller);

            assertNotNull(save);
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));
            assertTrue(loadCallStarted.await(5, TimeUnit.SECONDS));
            assertEquals(1L, loadStarted.getCount(), "same-player load ran while its prior save was blocked");
            releaseSave.countDown();
            CompletableFuture.allOf(save, load).get(5, TimeUnit.SECONDS);

            assertTrue(load.get());
            assertEquals(List.of("save-start", "save-end", "load"), order);
            queue.closeAndWait();
        } finally {
            releaseSave.countDown();
            synchronousCaller.shutdownNow();
            workers.shutdownNow();
            assertTrue(synchronousCaller.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void differentPlayersShareOneFifoDatabaseDrain() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        try {
            PreferenceManager.PlayerIoQueue queue = new PreferenceManager.PlayerIoQueue(workers);
            CompletableFuture<Void> first = queue.submit(UUID.randomUUID(), () -> {
                order.add("first-start");
                firstStarted.countDown();
                await(releaseFirst);
                order.add("first-end");
            });
            CompletableFuture<Void> second = queue.submit(UUID.randomUUID(), () -> {
                order.add("second");
                secondStarted.countDown();
            });

            assertNotNull(first);
            assertNotNull(second);
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS),
                    "a second player bypassed the global JDBC FIFO");
            releaseFirst.countDown();
            CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);
            assertEquals(List.of("first-start", "first-end", "second"), order);
            queue.closeAndWait();
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void viewerGroupReplacesReconnectInstancesWithoutAllocatingNewViews() {
        PreferenceManager.ViewerGroup group = new PreferenceManager.ViewerGroup();
        UUID uuid = UUID.randomUUID();
        Player oldPlayer = player(uuid, "old");
        Player reconnectedPlayer = player(uuid, "new");

        Collection<Player> view = group.view();
        assertSame(view, group.view());
        assertTrue(group.add(oldPlayer));
        assertTrue(group.add(reconnectedPlayer));
        assertFalse(group.remove(oldPlayer));
        assertTrue(view.contains(reconnectedPlayer));
        assertEquals(1, view.size());
        assertThrows(UnsupportedOperationException.class, view::clear);
        assertTrue(group.remove(reconnectedPlayer));
        assertTrue(view.isEmpty());
    }

    @Test
    void cachedServerViewTracksReloadedModuleSettingsWithoutRebuildingTheGroup() {
        boolean previousEnabled = InteractionVisualizer.hologramsEnabled;
        var previousDisabled = InteractionVisualizer.hologramsDisabled;
        try {
            InteractionVisualizer.hologramsEnabled = true;
            InteractionVisualizer.hologramsDisabled = new HashSet<>();
            EntryKey entry = new EntryKey("preference_cache_test");
            PreferenceManager.ViewerGroup group = new PreferenceManager.ViewerGroup(
                    Modules.HOLOGRAM, entry);
            Player player = player(UUID.randomUUID(), "viewer");
            group.add(player);

            Collection<Player> serverView = group.serverView();
            assertSame(serverView, group.serverView());
            assertTrue(serverView.contains(player));
            assertThrows(UnsupportedOperationException.class, serverView::clear);

            InteractionVisualizer.hologramsEnabled = false;
            assertTrue(serverView.isEmpty());
            assertEquals(1, group.view().size(),
                    "server setting changes must not discard the preference membership cache");
        } finally {
            InteractionVisualizer.hologramsEnabled = previousEnabled;
            InteractionVisualizer.hologramsDisabled = previousDisabled;
        }
    }

    @Test
    void sealingWaitsForQueuedFinalSaveAndRejectsLateWork() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch earlierIoStarted = new CountDownLatch(1);
        CountDownLatch releaseEarlierIo = new CountDownLatch(1);
        CountDownLatch finalSaveStarted = new CountDownLatch(1);
        CountDownLatch releaseFinalSave = new CountDownLatch(1);
        try {
            PreferenceManager.PlayerIoQueue queue = new PreferenceManager.PlayerIoQueue(worker);
            UUID uuid = UUID.randomUUID();
            queue.submit(uuid, () -> {
                earlierIoStarted.countDown();
                await(releaseEarlierIo);
            });
            queue.submit(uuid, () -> {
                finalSaveStarted.countDown();
                await(releaseFinalSave);
            });

            assertTrue(earlierIoStarted.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> closing = queue.seal();
            assertFalse(closing.isDone());
            assertNull(queue.submit(UUID.randomUUID(), () -> { }));

            releaseEarlierIo.countDown();
            assertTrue(finalSaveStarted.await(5, TimeUnit.SECONDS));
            assertFalse(closing.isDone(), "close completed while the final save was still blocked");
            releaseFinalSave.countDown();
            closing.get(5, TimeUnit.SECONDS);
            queue.closeAndWait();
        } finally {
            releaseEarlierIo.countDown();
            releaseFinalSave.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void synchronousReentryFromTheSamePlayerDrainFailsInsteadOfDeadlocking() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            PreferenceManager.PlayerIoQueue queue = new PreferenceManager.PlayerIoQueue(worker);
            UUID uuid = UUID.randomUUID();
            CompletableFuture<Void> outer = queue.submit(uuid,
                    () -> queue.runAndWait(uuid, () -> { }));

            assertNotNull(outer);
            ExecutionException exception = assertThrows(
                    ExecutionException.class, () -> outer.get(5, TimeUnit.SECONDS));
            assertSame(IllegalStateException.class, exception.getCause().getClass());
            IllegalStateException closeFailure = assertThrows(
                    IllegalStateException.class, queue::closeAndWait);
            assertSame(exception.getCause(), closeFailure);
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectedFinalSaveMakesCloseFailInsteadOfSilentlyDroppingIt() throws Exception {
        RejectedExecutionException rejection = new RejectedExecutionException("controlled rejection");
        PreferenceManager.PlayerIoQueue queue = new PreferenceManager.PlayerIoQueue(command -> {
            throw rejection;
        });

        CompletableFuture<Void> finalSave = queue.submit(UUID.randomUUID(), () -> { });
        assertNotNull(finalSave);
        ExecutionException operationFailure = assertThrows(
                ExecutionException.class, () -> finalSave.get(5, TimeUnit.SECONDS));
        assertSame(rejection, operationFailure.getCause());

        RejectedExecutionException closeFailure = assertThrows(
                RejectedExecutionException.class, queue::closeAndWait);
        assertSame(rejection, closeFailure);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for test latch", exception);
        }
    }

    private static Player player(UUID uuid, String name) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getUniqueId" -> uuid;
                        case "getName", "toString" -> name;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }
}
