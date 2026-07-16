/*
 * This file is part of InteractionVisualizer.
 *
 * Copyright (C) 2025. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2025. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.loohp.interactionvisualizer.managers;

import com.loohp.interactionvisualizer.InteractionVisualizer;
import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI.Modules;
import com.loohp.interactionvisualizer.database.Database;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.objectholders.SynchronizedFilteredCollection;
import com.loohp.interactionvisualizer.utils.ArrayUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

public class PreferenceManager implements Listener, AutoCloseable {

    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private final InteractionVisualizer plugin;
    private final List<EntryKey> entries;
    private final Map<UUID, Map<Modules, BitSet>> preferences;

    private final SynchronizedFilteredCollection<Player> backingPlayerList;

    private final AtomicBoolean valid;
    private final PlayerSessionGate playerSessions;
    private final ExecutorService playerIoExecutor;
    private final PlayerIoQueue playerIo;

    public PreferenceManager(InteractionVisualizer plugin) {
        this.plugin = plugin;
        this.valid = new AtomicBoolean(true);
        this.playerSessions = new PlayerSessionGate();
        this.playerIoExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("InteractionVisualizer-PlayerIO-", 0).factory());
        this.playerIo = new PlayerIoQueue(playerIoExecutor);
        this.entries = Collections.synchronizedList(ArrayUtils.putToArrayList(Database.getBitIndex(), new ArrayList<>()));
        this.preferences = new ConcurrentHashMap<>();
        this.backingPlayerList = SynchronizedFilteredCollection.from(new LinkedHashSet<>());
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Object session = playerSessions.open(uuid, valid);
            backingPlayerList.add(player);
            playerIo.runAndWait(uuid, () -> loadPlayerForSession(uuid, player.getName(), session));
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public synchronized void close() {
        if (!playerSessions.close(valid)) {
            return;
        }
        backingPlayerList.clear();
        Map<UUID, Map<Modules, BitSet>> snapshots = new HashMap<>();
        for (Entry<UUID, Map<Modules, BitSet>> entry : preferences.entrySet()) {
            snapshots.put(entry.getKey(), copyPreferences(entry.getValue()));
        }
        preferences.clear();
        for (Entry<UUID, Map<Modules, BitSet>> entry : snapshots.entrySet()) {
            playerIo.submit(entry.getKey(), () -> savePlayerInfo(entry.getKey(), entry.getValue()));
        }
        RuntimeException playerIoFailure = null;
        try {
            playerIo.closeAndWait();
        } catch (RuntimeException exception) {
            playerIoFailure = exception;
        } finally {
            playerIoExecutor.shutdown();
        }
        saveBitmaskIndex();
        if (playerIoFailure != null) {
            plugin.getLogger().log(Level.SEVERE,
                    "One or more queued player preference operations failed before shutdown", playerIoFailure);
        }
    }

    public boolean isValid() {
        return valid.get();
    }

    public void saveBitmaskIndex() {
        Bukkit.getConsoleSender().sendMessage(Component.text(
                "[InteractionVisualizer] Saving player preferences bitmask index, do not halt the server.", NamedTextColor.AQUA));
        synchronized (entries) {
            Database.runExclusive(() -> Database.setBitIndex(ArrayUtils.putToMap(entries, new HashMap<>())));
        }
    }

    public void registerEntry(EntryKey entryKey) {
        registerEntry(Collections.singletonList(entryKey));
    }

    public void registerEntry(EntryKey... entryKeys) {
        if (entryKeys.length == 0) {
            return;
        }
        registerEntry(Arrays.asList(entryKeys));
    }

    public void registerEntry(List<EntryKey> entryKeys) {
        if (!entryKeys.isEmpty()) {
            synchronized (entries) {
                Database.runExclusive(() -> {
                    List<EntryKey> updatedEntries = ArrayUtils.putToArrayList(Database.getBitIndex(), new ArrayList<>());
                    entries.clear();
                    entries.addAll(updatedEntries);
                    boolean changes = false;
                    for (EntryKey entry : entryKeys) {
                        if (!entries.contains(entry)) {
                            changes = true;
                            entries.add(entry);
                        }
                    }
                    if (changes) {
                        Database.setBitIndex(ArrayUtils.putToMap(entries, new HashMap<>()));
                    }
                });
            }
        }
    }

    @EventHandler
    public void onJoinEvent(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Object session = playerSessions.open(uuid, valid);
        if (session == null) {
            return;
        }
        backingPlayerList.add(player);
        playerIo.submit(uuid, () -> {
            if (loadPlayerForSession(uuid, name, session)) {
                InteractionVisualizer.asyncExecutorManager.runTaskSynchronously(() -> {
                    if (valid.get() && playerSessions.isCurrent(uuid, session) && player.isOnline()) {
                        updatePlayer(player, false);
                    }
                });
            }
        });
    }

    @EventHandler
    public void onQuitEvent(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        backingPlayerList.remove(player);
        playerSessions.end(uuid);
        Map<Modules, BitSet> snapshot = copyPreferences(preferences.remove(uuid));
        playerIo.submit(uuid, () -> savePlayerInfo(uuid, snapshot));
    }

    public void loadPlayer(UUID uuid, String name, boolean createIfNotFound) {
        if (!valid.get()) {
            return;
        }
        playerIo.runAndWait(uuid, () -> {
            Map<Modules, BitSet> info = readPlayerInfo(uuid, name, createIfNotFound);
            if (info != null) {
                playerSessions.publishIfValid(valid, () -> preferences.put(uuid, info));
            }
        });
    }

    private boolean loadPlayerForSession(UUID uuid, String name, Object session) {
        Map<Modules, BitSet> info = readPlayerInfo(uuid, name, true);
        return info != null && playerSessions.publishIfCurrent(
                uuid, session, valid, () -> preferences.put(uuid, info));
    }

    private Map<Modules, BitSet> readPlayerInfo(UUID uuid, String name, boolean createIfNotFound) {
        if (createIfNotFound) {
            boolean newPlayer = false;
            if (!Database.playerExists(uuid)) {
                Database.createPlayer(uuid, name);
                newPlayer = true;
            }
            Map<Modules, BitSet> info = Database.getPlayerInfo(uuid);
            if (newPlayer && InteractionVisualizer.defaultDisabledAll) {
                disableAll(info);
                savePlayerInfo(uuid, info);
            }
            return info;
        } else {
            if (Database.playerExists(uuid)) {
                return Database.getPlayerInfo(uuid);
            }
            return null;
        }
    }

    public void savePlayer(UUID uuid, boolean unload) {
        Map<Modules, BitSet> info = copyPreferences(unload ? preferences.remove(uuid) : preferences.get(uuid));
        playerIo.runAndWait(uuid, () -> savePlayerInfo(uuid, info));
    }

    private void savePlayerInfo(UUID uuid, Map<Modules, BitSet> info) {
        if (info != null) {
            for (Entry<Modules, BitSet> entry : info.entrySet()) {
                switch (entry.getKey()) {
                    case HOLOGRAM:
                        Database.setHologram(uuid, entry.getValue());
                        break;
                    case ITEMDROP:
                        Database.setItemDrop(uuid, entry.getValue());
                        break;
                    case ITEMSTAND:
                        Database.setItemStand(uuid, entry.getValue());
                        break;
                }
            }
        }
    }

    private void disableAll(Map<Modules, BitSet> info) {
        synchronized (entries) {
            int entryCount = entries.size();
            for (Modules module : Modules.values()) {
                info.computeIfAbsent(module, ignored -> new BitSet()).set(0, entryCount, true);
            }
        }
    }

    private static Map<Modules, BitSet> copyPreferences(Map<Modules, BitSet> source) {
        if (source == null) {
            return null;
        }
        Map<Modules, BitSet> copy = new HashMap<>();
        for (Entry<Modules, BitSet> entry : source.entrySet()) {
            copy.put(entry.getKey(), (BitSet) entry.getValue().clone());
        }
        return copy;
    }

    public void unloadPlayerWithoutSaving(UUID uuid) {
        preferences.remove(uuid);
    }

    public void updatePlayer(Player player, boolean reset) {
        if (reset) {
            DisplayManager.reset(player);
        } else {
            DisplayManager.sendPlayerPackets(player);
        }
    }

    public boolean isRegisteredEntry(EntryKey entry) {
        return entries.contains(entry);
    }

    public List<EntryKey> getRegisteredEntries() {
        return Collections.unmodifiableList(entries);
    }

    public boolean getPlayerPreference(UUID uuid, Modules module, EntryKey entry) {
        int i = entries.indexOf(entry);
        if (i < 0) {
            return false;
        }
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            BitSet bitset = info.get(module);
            return !bitset.get(i);
        } else {
            return false;
        }
    }

    public Map<Modules, Map<EntryKey, Boolean>> getPlayerPreferences(UUID uuid) {
        Map<Modules, Map<EntryKey, Boolean>> preferences = new HashMap<>();
        for (Modules module : Modules.values()) {
            Map<EntryKey, Boolean> entryPreference = new HashMap<>();
            for (EntryKey entry : getRegisteredEntries()) {
                entryPreference.put(entry, getPlayerPreference(uuid, module, entry));
            }
            preferences.put(module, entryPreference);
        }
        return preferences;
    }

    public void setPlayerPreference(UUID uuid, Modules module, EntryKey entry, boolean enabled, boolean update) {
        int i = entries.indexOf(entry);
        if (i < 0) {
            return;
        }
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            BitSet bitset = info.get(module);
            bitset.set(i, !enabled);
        }
        if (update) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                updatePlayer(player, true);
            }
        }
    }

    public void setPlayerAllPreference(UUID uuid, Modules module, boolean enabled, boolean update) {
        for (EntryKey entry : getRegisteredEntries()) {
            setPlayerPreference(uuid, module, entry, enabled, false);
        }
        if (update) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                updatePlayer(player, true);
            }
        }
    }

    public void setPlayerAllPreference(UUID uuid, EntryKey entry, boolean enabled, boolean update) {
        for (Modules module : Modules.values()) {
            setPlayerPreference(uuid, module, entry, enabled, false);
        }
        if (update) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                updatePlayer(player, true);
            }
        }
    }

    public void setPlayerAllPreference(UUID uuid, boolean enabled, boolean update) {
        for (Modules module : Modules.values()) {
            for (EntryKey entry : getRegisteredEntries()) {
                setPlayerPreference(uuid, module, entry, enabled, false);
            }
        }
        if (update) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                updatePlayer(player, true);
            }
        }
    }

    public boolean hasAnyPreferenceDisabled(UUID uuid, Modules module) {
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            BitSet bitset = info.get(module);
            return bitset.cardinality() > 0;
        }
        return false;
    }

    public boolean hasAnyPreferenceDisabled(UUID uuid, EntryKey entry) {
        int i = entries.indexOf(entry);
        if (i < 0) {
            return false;
        }
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            for (BitSet bitset : info.values()) {
                if (bitset.get(i)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean hasAnyPreferenceDisabled(UUID uuid) {
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            for (BitSet bitset : info.values()) {
                if (bitset.cardinality() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean hasAnyPreferenceEnabled(UUID uuid, Modules module) {
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            BitSet bitset = info.get(module);
            for (int i = 0; i < entries.size(); i++) {
                if (!bitset.get(i)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean hasAnyPreferenceEnabled(UUID uuid, EntryKey entry) {
        int i = entries.indexOf(entry);
        if (i < 0) {
            return false;
        }
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            for (BitSet bitset : info.values()) {
                if (!bitset.get(i)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean hasAnyPreferenceEnabled(UUID uuid) {
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            for (BitSet bitset : info.values()) {
                for (int i = 0; i < entries.size(); i++) {
                    if (!bitset.get(i)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean hasAllPreferenceEnabled(UUID uuid, Modules module) {
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            BitSet bitset = info.get(module);
            for (int i = 0; i < entries.size(); i++) {
                if (bitset.get(i)) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean hasAllPreferenceEnabled(UUID uuid, EntryKey entry) {
        int i = entries.indexOf(entry);
        if (i < 0) {
            return false;
        }
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            for (BitSet bitset : info.values()) {
                if (bitset.get(i)) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean hasAllPreferenceEnabled(UUID uuid) {
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            for (BitSet bitset : info.values()) {
                for (int i = 0; i < entries.size(); i++) {
                    if (bitset.get(i)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public Collection<Player> getPlayerList(Modules module, EntryKey entry) {
        BooleanSupplier serverSetting;
        switch (module) {
            case HOLOGRAM:
                serverSetting = () -> InteractionVisualizer.hologramsEnabled && !InteractionVisualizer.hologramsDisabled.contains(entry);
                break;
            case ITEMDROP:
                serverSetting = () -> InteractionVisualizer.itemDropEnabled && !InteractionVisualizer.itemDropDisabled.contains(entry);
                break;
            case ITEMSTAND:
                serverSetting = () -> InteractionVisualizer.itemStandEnabled && !InteractionVisualizer.itemStandDisabled.contains(entry);
                break;
            default:
                serverSetting = () -> true;
                break;
        }
        return SynchronizedFilteredCollection.filter(backingPlayerList, player -> {
            if (!serverSetting.getAsBoolean()) {
                return false;
            }
            if (!isRegisteredEntry(entry)) {
                return false;
            }
            return getPlayerPreference(player.getUniqueId(), module, entry);
        });
    }

    public Collection<Player> getPlayerListIgnoreServerSetting(Modules module, EntryKey entry) {
        return SynchronizedFilteredCollection.filter(backingPlayerList, player -> {
            if (!isRegisteredEntry(entry)) {
                return false;
            }
            return getPlayerPreference(player.getUniqueId(), module, entry);
        });
    }

    public Collection<Player> getPlayerList() {
        return Collections.unmodifiableCollection(backingPlayerList);
    }

    static final class PlayerSessionGate {

        private final Map<UUID, Object> sessions = new HashMap<>();

        synchronized Object open(UUID uuid, AtomicBoolean valid) {
            if (!valid.get()) {
                return null;
            }
            Object session = new Object();
            sessions.put(uuid, session);
            return session;
        }

        synchronized void end(UUID uuid) {
            sessions.remove(uuid);
        }

        synchronized boolean isCurrent(UUID uuid, Object session) {
            return sessions.get(uuid) == session;
        }

        synchronized boolean publishIfCurrent(UUID uuid, Object session, AtomicBoolean valid, Runnable publish) {
            if (!valid.get() || sessions.get(uuid) != session) {
                return false;
            }
            publish.run();
            return true;
        }

        synchronized boolean publishIfValid(AtomicBoolean valid, Runnable publish) {
            if (!valid.get()) {
                return false;
            }
            publish.run();
            return true;
        }

        synchronized boolean close(AtomicBoolean valid) {
            if (!valid.compareAndSet(true, false)) {
                return false;
            }
            sessions.clear();
            return true;
        }
    }

    /**
     * Serializes database work for one player without coupling unrelated players to the same queue.
     */
    static final class PlayerIoQueue {

        private final Executor executor;
        private final Map<UUID, QueueState> queues = new HashMap<>();
        private final ThreadLocal<UUID> activePlayer = new ThreadLocal<>();
        private boolean accepting = true;
        private CompletableFuture<Void> closed;
        private Throwable failure;

        PlayerIoQueue(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
        }

        CompletableFuture<Void> submit(UUID uuid, Runnable operation) {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(operation, "operation");

            QueuedOperation queued = new QueuedOperation(operation);
            QueueState state;
            boolean startDrain;
            synchronized (this) {
                if (!accepting) {
                    return null;
                }
                state = queues.computeIfAbsent(uuid, ignored -> new QueueState());
                state.operations.add(queued);
                startDrain = !state.running;
                if (startDrain) {
                    state.running = true;
                }
            }

            if (startDrain) {
                try {
                    executor.execute(() -> drain(uuid, state));
                } catch (RuntimeException | Error throwable) {
                    reject(uuid, state, throwable);
                }
            }
            return queued.completion;
        }

        boolean runAndWait(UUID uuid, Runnable operation) {
            if (uuid.equals(activePlayer.get())) {
                throw new IllegalStateException("Cannot synchronously enqueue player I/O from its own queue");
            }
            CompletableFuture<Void> completion = submit(uuid, operation);
            if (completion == null) {
                return false;
            }
            await(completion);
            return true;
        }

        void closeAndWait() {
            if (activePlayer.get() != null) {
                throw new IllegalStateException("Cannot close the player I/O queue from one of its drains");
            }
            await(seal());
        }

        synchronized CompletableFuture<Void> seal() {
            if (closed != null) {
                return closed;
            }
            accepting = false;
            closed = new CompletableFuture<>();
            completeCloseIfIdle();
            return closed;
        }

        private void drain(UUID uuid, QueueState state) {
            while (true) {
                QueuedOperation queued;
                synchronized (this) {
                    queued = state.operations.poll();
                    if (queued == null) {
                        state.running = false;
                        if (queues.get(uuid) == state) {
                            queues.remove(uuid);
                        }
                        completeCloseIfIdle();
                        return;
                    }
                }

                activePlayer.set(uuid);
                try {
                    queued.operation.run();
                    queued.completion.complete(null);
                } catch (Throwable throwable) {
                    recordFailure(throwable);
                    queued.completion.completeExceptionally(throwable);
                } finally {
                    activePlayer.remove();
                }
            }
        }

        private void reject(UUID uuid, QueueState state, Throwable throwable) {
            List<QueuedOperation> rejected = new ArrayList<>();
            synchronized (this) {
                if (queues.get(uuid) != state) {
                    return;
                }
                recordFailureLocked(throwable);
                state.running = false;
                QueuedOperation queued;
                while ((queued = state.operations.poll()) != null) {
                    rejected.add(queued);
                }
                queues.remove(uuid);
                completeCloseIfIdle();
            }
            for (QueuedOperation queued : rejected) {
                queued.completion.completeExceptionally(throwable);
            }
        }

        private void completeCloseIfIdle() {
            if (closed != null && queues.isEmpty()) {
                if (failure == null) {
                    closed.complete(null);
                } else {
                    closed.completeExceptionally(failure);
                }
            }
        }

        private synchronized void recordFailure(Throwable throwable) {
            recordFailureLocked(throwable);
        }

        private void recordFailureLocked(Throwable throwable) {
            if (failure == null) {
                failure = throwable;
            }
        }

        private static void await(CompletableFuture<Void> completion) {
            try {
                completion.join();
            } catch (CompletionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw exception;
            }
        }

        private static final class QueueState {

            private final Queue<QueuedOperation> operations = new ArrayDeque<>();
            private boolean running;
        }

        private static final class QueuedOperation {

            private final Runnable operation;
            private final CompletableFuture<Void> completion = new CompletableFuture<>();

            private QueuedOperation(Runnable operation) {
                this.operation = operation;
            }
        }
    }

}
