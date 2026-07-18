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
import com.loohp.interactionvisualizer.utils.ArrayUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.AbstractCollection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.logging.Level;

public class PreferenceManager implements Listener, AutoCloseable {

    private static final UUID SYSTEM_IO_KEY = new UUID(0L, 0L);

    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private final InteractionVisualizer plugin;
    private final List<EntryKey> entries;
    private volatile Map<EntryKey, Integer> entryIndexes;
    private volatile List<EntryKey> registeredEntries;
    private final Map<UUID, Map<Modules, BitSet>> preferences;
    private final ViewerGroup backingPlayerList;
    private final Map<EntryKey, EnumMap<Modules, ViewerGroup>> viewerGroups;

    private final AtomicBoolean valid;
    private final PlayerSessionGate playerSessions;
    private final ExecutorService playerIoExecutor;
    private final PlayerIoQueue playerIo;

    public PreferenceManager(InteractionVisualizer plugin) {
        this.plugin = plugin;
        this.valid = new AtomicBoolean(true);
        this.playerSessions = new PlayerSessionGate();
        this.playerIoExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("InteractionVisualizer-PreferenceIO").factory());
        this.playerIo = new PlayerIoQueue(playerIoExecutor);
        AtomicReference<Map<Integer, EntryKey>> loadedIndex = new AtomicReference<>();
        try {
            playerIo.runAndWait(SYSTEM_IO_KEY, () -> loadedIndex.set(Database.getBitIndex()));
        } catch (RuntimeException | Error exception) {
            playerIoExecutor.shutdownNow();
            Database.close();
            throw exception;
        }
        this.entries = Collections.synchronizedList(
                ArrayUtils.putToArrayList(loadedIndex.get(), new ArrayList<>()));
        this.entryIndexes = Map.of();
        this.registeredEntries = List.of();
        this.preferences = new ConcurrentHashMap<>();
        this.backingPlayerList = new ViewerGroup();
        this.viewerGroups = new ConcurrentHashMap<>();
        rebuildEntryCache();
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                Object session = playerSessions.open(uuid, valid);
                backingPlayerList.add(player);
                playerIo.runAndWait(uuid,
                        () -> loadPlayerForSession(uuid, player.getName(), session, player));
            }
        } catch (RuntimeException | Error exception) {
            playerSessions.close(valid);
            try {
                playerIo.closeAndWait();
            } catch (RuntimeException | Error closeFailure) {
                if (closeFailure != exception) {
                    exception.addSuppressed(closeFailure);
                }
            } finally {
                playerIoExecutor.shutdownNow();
                Database.close();
            }
            throw exception;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public synchronized void close() {
        if (!playerSessions.close(valid)) {
            return;
        }
        Map<UUID, Map<Modules, BitSet>> snapshots = new HashMap<>();
        for (Entry<UUID, Map<Modules, BitSet>> entry : preferences.entrySet()) {
            snapshots.put(entry.getKey(), copyPreferences(entry.getValue()));
        }
        preferences.clear();
        backingPlayerList.clear();
        for (EnumMap<Modules, ViewerGroup> groups : viewerGroups.values()) {
            groups.values().forEach(ViewerGroup::clear);
        }
        for (Entry<UUID, Map<Modules, BitSet>> entry : snapshots.entrySet()) {
            playerIo.submit(entry.getKey(), () -> savePlayerInfo(entry.getKey(), entry.getValue()));
        }
        Map<Integer, EntryKey> indexSnapshot;
        synchronized (entries) {
            indexSnapshot = ArrayUtils.putToMap(entries, new HashMap<>());
        }
        playerIo.submit(SYSTEM_IO_KEY, () -> Database.setBitIndex(indexSnapshot));
        RuntimeException playerIoFailure = null;
        try {
            playerIo.closeAndWait();
        } catch (RuntimeException exception) {
            playerIoFailure = exception;
        } finally {
            playerIoExecutor.shutdown();
            Database.close();
        }
        if (playerIoFailure != null) {
            plugin.getLogger().log(Level.SEVERE,
                    "One or more queued player preference operations failed before shutdown", playerIoFailure);
        }
    }

    public boolean isValid() {
        return valid.get();
    }

    public void saveBitmaskIndex() {
        if (!valid.get()) {
            return;
        }
        Bukkit.getConsoleSender().sendMessage(Component.text(
                "[InteractionVisualizer] Saving player preferences bitmask index, do not halt the server.", NamedTextColor.AQUA));
        Map<Integer, EntryKey> snapshot;
        synchronized (entries) {
            snapshot = ArrayUtils.putToMap(entries, new HashMap<>());
        }
        playerIo.runAndWait(SYSTEM_IO_KEY, () -> Database.setBitIndex(snapshot));
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
        if (entryKeys.isEmpty() || !valid.get()) {
            return;
        }
        boolean changed = false;
        Map<Integer, EntryKey> snapshot = null;
        synchronized (entries) {
            for (EntryKey entry : entryKeys) {
                if (entry != null && !entries.contains(entry)) {
                    entries.add(entry);
                    changed = true;
                }
            }
            if (changed) {
                rebuildEntryCache();
                snapshot = ArrayUtils.putToMap(entries, new HashMap<>());
            }
        }
        if (changed) {
            Map<Integer, EntryKey> indexSnapshot = snapshot;
            playerIo.runAndWait(SYSTEM_IO_KEY, () -> Database.setBitIndex(indexSnapshot));
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
        submitAsync(uuid, "load player preferences", () -> {
            if (loadPlayerForSession(uuid, name, session, player)) {
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
        playerSessions.end(uuid);
        backingPlayerList.remove(player);
        removeFromViewerGroups(player);
        Map<Modules, BitSet> snapshot = copyPreferences(preferences.remove(uuid));
        submitAsync(uuid, "save player preferences", () -> savePlayerInfo(uuid, snapshot));
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

    private boolean loadPlayerForSession(UUID uuid, String name, Object session, Player player) {
        Map<Modules, BitSet> info = readPlayerInfo(uuid, name, true);
        return info != null && playerSessions.publishIfCurrent(
                uuid, session, valid, () -> {
                    preferences.put(uuid, info);
                    if (player != null) {
                        updateViewerGroups(player, info);
                    }
                });
    }

    private Map<Modules, BitSet> readPlayerInfo(UUID uuid, String name, boolean createIfNotFound) {
        BitSet defaults = null;
        if (createIfNotFound) {
            defaults = new BitSet();
            if (InteractionVisualizer.defaultDisabledAll) {
                defaults.set(0, entryCount(), true);
            }
        }
        return Database.loadPlayer(uuid, name, createIfNotFound, defaults);
    }

    public void savePlayer(UUID uuid, boolean unload) {
        if (!valid.get()) {
            return;
        }
        Map<Modules, BitSet> info = copyPreferences(unload ? preferences.remove(uuid) : preferences.get(uuid));
        if (unload) {
            Player player = backingPlayerList.get(uuid);
            if (player != null) {
                removeFromViewerGroups(player);
            }
        }
        playerIo.runAndWait(uuid, () -> savePlayerInfo(uuid, info));
    }

    private void savePlayerInfo(UUID uuid, Map<Modules, BitSet> info) {
        if (info != null) {
            Database.savePlayer(uuid, info);
        }
    }

    private void submitAsync(UUID uuid, String description, Runnable operation) {
        CompletableFuture<Void> completion = playerIo.submit(uuid, operation);
        if (completion != null) {
            completion.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Unable to " + description + " for " + uuid, failure);
                }
            });
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
        Player player = backingPlayerList.get(uuid);
        if (player != null) {
            removeFromViewerGroups(player);
        }
    }

    public void updatePlayer(Player player, boolean reset) {
        if (reset) {
            DisplayManager.reset(player);
        } else {
            DisplayManager.sendPlayerPackets(player);
        }
    }

    public boolean isRegisteredEntry(EntryKey entry) {
        return entryIndexes.containsKey(entry);
    }

    public List<EntryKey> getRegisteredEntries() {
        return registeredEntries;
    }

    public boolean getPlayerPreference(UUID uuid, Modules module, EntryKey entry) {
        Integer index = entryIndexes.get(entry);
        if (index == null) {
            return false;
        }
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            BitSet bitset = info.get(module);
            return bitset != null && !bitset.get(index);
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
        Integer index = entryIndexes.get(entry);
        if (index == null) {
            return;
        }
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            BitSet bitset = info.computeIfAbsent(module, ignored -> new BitSet());
            bitset.set(index, !enabled);
            updateViewerGroup(uuid, module, entry, enabled);
        }
        if (update) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                updatePlayer(player, true);
            }
        }
    }

    public void setPlayerAllPreference(UUID uuid, Modules module, boolean enabled, boolean update) {
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            info.computeIfAbsent(module, ignored -> new BitSet())
                    .set(0, entryCount(), !enabled);
            for (EntryKey entry : registeredEntries) {
                updateViewerGroup(uuid, module, entry, enabled);
            }
        }
        if (update) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                updatePlayer(player, true);
            }
        }
    }

    public void setPlayerAllPreference(UUID uuid, EntryKey entry, boolean enabled, boolean update) {
        Integer index = entryIndexes.get(entry);
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (index != null && info != null) {
            for (Modules module : Modules.values()) {
                info.computeIfAbsent(module, ignored -> new BitSet()).set(index, !enabled);
                updateViewerGroup(uuid, module, entry, enabled);
            }
        }
        if (update) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                updatePlayer(player, true);
            }
        }
    }

    public void setPlayerAllPreference(UUID uuid, boolean enabled, boolean update) {
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            int entryCount = entryCount();
            for (Modules module : Modules.values()) {
                info.computeIfAbsent(module, ignored -> new BitSet())
                        .set(0, entryCount, !enabled);
                for (EntryKey entry : registeredEntries) {
                    updateViewerGroup(uuid, module, entry, enabled);
                }
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
        Integer index = entryIndexes.get(entry);
        if (index == null) {
            return false;
        }
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            for (BitSet bitset : info.values()) {
                if (bitset.get(index)) {
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
            for (int i = 0; i < entryCount(); i++) {
                if (!bitset.get(i)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean hasAnyPreferenceEnabled(UUID uuid, EntryKey entry) {
        Integer index = entryIndexes.get(entry);
        if (index == null) {
            return false;
        }
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            for (BitSet bitset : info.values()) {
                if (!bitset.get(index)) {
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
                for (int i = 0; i < entryCount(); i++) {
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
            for (int i = 0; i < entryCount(); i++) {
                if (bitset.get(i)) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean hasAllPreferenceEnabled(UUID uuid, EntryKey entry) {
        Integer index = entryIndexes.get(entry);
        if (index == null) {
            return false;
        }
        Map<Modules, BitSet> info = preferences.get(uuid);
        if (info != null) {
            for (BitSet bitset : info.values()) {
                if (bitset.get(index)) {
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
                for (int i = 0; i < entryCount(); i++) {
                    if (bitset.get(i)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public Collection<Player> getPlayerList(Modules module, EntryKey entry) {
        return getViewerGroup(module, entry, true);
    }

    public Collection<Player> getPlayerListIgnoreServerSetting(Modules module, EntryKey entry) {
        return getViewerGroup(module, entry, false);
    }

    public Collection<Player> getPlayerList() {
        return backingPlayerList.view();
    }

    private int entryCount() {
        synchronized (entries) {
            return entries.size();
        }
    }

    private void rebuildEntryCache() {
        Map<EntryKey, Integer> indexes = new HashMap<>();
        List<EntryKey> registered = new ArrayList<>();
        synchronized (entries) {
            for (int index = 0; index < entries.size(); index++) {
                EntryKey entry = entries.get(index);
                if (entry != null) {
                    indexes.put(entry, index);
                    registered.add(entry);
                }
            }
        }
        synchronized (viewerGroups) {
            for (EntryKey entry : registered) {
                if (viewerGroups.containsKey(entry)) {
                    continue;
                }
                int index = indexes.get(entry);
                EnumMap<Modules, ViewerGroup> groups = new EnumMap<>(Modules.class);
                for (Modules module : Modules.values()) {
                    ViewerGroup group = new ViewerGroup(module, entry);
                    for (Player player : backingPlayerList) {
                        if (preferenceEnabled(preferences.get(player.getUniqueId()), module, index)) {
                            group.add(player);
                        }
                    }
                    groups.put(module, group);
                }
                viewerGroups.put(entry, groups);
            }
            entryIndexes = Collections.unmodifiableMap(indexes);
            registeredEntries = Collections.unmodifiableList(registered);
        }
    }

    private void updateViewerGroups(Player player, Map<Modules, BitSet> info) {
        synchronized (viewerGroups) {
            for (Entry<EntryKey, Integer> indexedEntry : entryIndexes.entrySet()) {
                EnumMap<Modules, ViewerGroup> groups = viewerGroups.get(indexedEntry.getKey());
                if (groups == null) {
                    continue;
                }
                for (Modules module : Modules.values()) {
                    ViewerGroup group = groups.get(module);
                    if (preferenceEnabled(info, module, indexedEntry.getValue())) {
                        group.add(player);
                    } else {
                        group.remove(player);
                    }
                }
            }
        }
    }

    private void updateViewerGroup(UUID uuid, Modules module, EntryKey entry, boolean enabled) {
        EnumMap<Modules, ViewerGroup> groups = viewerGroups.get(entry);
        ViewerGroup group = groups == null ? null : groups.get(module);
        if (group == null) {
            return;
        }
        Player player = backingPlayerList.get(uuid);
        if (enabled && player != null) {
            group.add(player);
        } else {
            group.remove(uuid);
        }
    }

    private void removeFromViewerGroups(Player player) {
        for (EnumMap<Modules, ViewerGroup> groups : viewerGroups.values()) {
            for (ViewerGroup group : groups.values()) {
                group.remove(player);
            }
        }
    }

    private Collection<Player> getViewerGroup(Modules module, EntryKey entry,
                                               boolean honorServerSetting) {
        EnumMap<Modules, ViewerGroup> groups = viewerGroups.get(entry);
        ViewerGroup group = groups == null ? null : groups.get(module);
        if (group == null) {
            return List.of();
        }
        return honorServerSetting ? group.serverView() : group.view();
    }

    private static boolean preferenceEnabled(Map<Modules, BitSet> info, Modules module, int index) {
        if (info == null) {
            return false;
        }
        BitSet disabled = info.get(module);
        return disabled != null && !disabled.get(index);
    }

    private static boolean serverSettingEnabled(Modules module, EntryKey entry) {
        return switch (module) {
            case HOLOGRAM -> InteractionVisualizer.hologramsEnabled
                    && !InteractionVisualizer.hologramsDisabled.contains(entry);
            case ITEMDROP -> InteractionVisualizer.itemDropEnabled
                    && !InteractionVisualizer.itemDropDisabled.contains(entry);
            case ITEMSTAND -> InteractionVisualizer.itemStandEnabled
                    && !InteractionVisualizer.itemStandDisabled.contains(entry);
        };
    }

    /** Concurrent, snapshot-free membership view updated only on state changes. */
    interface ViewerMembership {

        boolean containsViewer(UUID viewer);
    }

    static final class ViewerGroup extends AbstractCollection<Player> implements ViewerMembership {

        private final ConcurrentHashMap<UUID, Player> players = new ConcurrentHashMap<>();
        private final Collection<Player> readOnly = Collections.unmodifiableCollection(this);
        private final Collection<Player> serverView;

        ViewerGroup() {
            this.serverView = readOnly;
        }

        ViewerGroup(Modules module, EntryKey entry) {
            this.serverView = new ServerSettingView(this, module, entry);
        }

        @Override
        public boolean add(Player player) {
            Objects.requireNonNull(player, "player");
            return players.put(player.getUniqueId(), player) != player;
        }

        @Override
        public boolean remove(Object value) {
            if (!(value instanceof Player player)) {
                return false;
            }
            return players.remove(player.getUniqueId(), player);
        }

        boolean remove(UUID uuid) {
            return players.remove(uuid) != null;
        }

        Player get(UUID uuid) {
            return players.get(uuid);
        }

        Collection<Player> view() {
            return readOnly;
        }

        Collection<Player> serverView() {
            return serverView;
        }

        @Override
        public Iterator<Player> iterator() {
            return players.values().iterator();
        }

        @Override
        public int size() {
            return players.size();
        }

        @Override
        public boolean isEmpty() {
            return players.isEmpty();
        }

        @Override
        public boolean contains(Object value) {
            if (!(value instanceof Player player)) {
                return false;
            }
            Player current = players.get(player.getUniqueId());
            return current == player || player.equals(current);
        }

        @Override
        public boolean containsViewer(UUID viewer) {
            return players.containsKey(viewer);
        }

        @Override
        public void clear() {
            players.clear();
        }

        private static final class ServerSettingView extends AbstractCollection<Player>
                implements ViewerMembership {

            private final ViewerGroup delegate;
            private final Modules module;
            private final EntryKey entry;

            private ServerSettingView(ViewerGroup delegate, Modules module, EntryKey entry) {
                this.delegate = delegate;
                this.module = module;
                this.entry = entry;
            }

            @Override
            public Iterator<Player> iterator() {
                return serverSettingEnabled(module, entry)
                        ? delegate.view().iterator() : Collections.emptyIterator();
            }

            @Override
            public int size() {
                return serverSettingEnabled(module, entry) ? delegate.size() : 0;
            }

            @Override
            public boolean isEmpty() {
                return !serverSettingEnabled(module, entry) || delegate.isEmpty();
            }

            @Override
            public boolean contains(Object value) {
                return serverSettingEnabled(module, entry) && delegate.contains(value);
            }

            @Override
            public boolean containsViewer(UUID viewer) {
                return serverSettingEnabled(module, entry) && delegate.containsViewer(viewer);
            }

            @Override
            public boolean add(Player player) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean addAll(Collection<? extends Player> collection) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean remove(Object value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean removeAll(Collection<?> collection) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean retainAll(Collection<?> collection) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean removeIf(Predicate<? super Player> filter) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void clear() {
                throw new UnsupportedOperationException();
            }
        }
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

    /** One global FIFO matching the single persistent JDBC connection. */
    static final class PlayerIoQueue {

        private final Executor executor;
        private final Queue<QueuedOperation> operations = new ArrayDeque<>();
        private final ThreadLocal<UUID> activePlayer = new ThreadLocal<>();
        private boolean running;
        private boolean accepting = true;
        private CompletableFuture<Void> closed;
        private Throwable failure;

        PlayerIoQueue(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
        }

        CompletableFuture<Void> submit(UUID uuid, Runnable operation) {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(operation, "operation");

            QueuedOperation queued = new QueuedOperation(uuid, operation);
            boolean startDrain;
            synchronized (this) {
                if (!accepting) {
                    return null;
                }
                operations.add(queued);
                PerformanceMetrics.preferenceIoQueueDepth(operations.size());
                startDrain = !running;
                if (startDrain) {
                    running = true;
                }
            }

            if (startDrain) {
                try {
                    executor.execute(this::drain);
                } catch (RuntimeException | Error throwable) {
                    reject(throwable);
                }
            }
            return queued.completion;
        }

        boolean runAndWait(UUID uuid, Runnable operation) {
            if (activePlayer.get() != null) {
                throw new IllegalStateException("Cannot synchronously enqueue preference I/O from its own queue");
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

        private void drain() {
            while (true) {
                QueuedOperation queued;
                synchronized (this) {
                    queued = operations.poll();
                    if (queued == null) {
                        running = false;
                        completeCloseIfIdle();
                        return;
                    }
                }

                activePlayer.set(queued.uuid);
                try {
                    PerformanceMetrics.preferenceIoOperation();
                    queued.operation.run();
                    queued.completion.complete(null);
                } catch (Throwable throwable) {
                    PerformanceMetrics.preferenceIoFailure();
                    recordFailure(throwable);
                    queued.completion.completeExceptionally(throwable);
                } finally {
                    activePlayer.remove();
                }
            }
        }

        private void reject(Throwable throwable) {
            List<QueuedOperation> rejected = new ArrayList<>();
            synchronized (this) {
                if (!running) {
                    return;
                }
                recordFailureLocked(throwable);
                running = false;
                QueuedOperation queued;
                while ((queued = operations.poll()) != null) {
                    rejected.add(queued);
                }
                completeCloseIfIdle();
            }
            PerformanceMetrics.preferenceIoFailure();
            for (QueuedOperation queued : rejected) {
                queued.completion.completeExceptionally(throwable);
            }
        }

        private void completeCloseIfIdle() {
            if (closed != null && !running && operations.isEmpty()) {
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

        private static final class QueuedOperation {

            private final UUID uuid;
            private final Runnable operation;
            private final CompletableFuture<Void> completion = new CompletableFuture<>();

            private QueuedOperation(UUID uuid, Runnable operation) {
                this.uuid = uuid;
                this.operation = operation;
            }
        }
    }

}
