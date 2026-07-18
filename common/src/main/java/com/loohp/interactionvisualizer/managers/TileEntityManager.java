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
import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI;
import com.loohp.interactionvisualizer.api.events.TileEntityAddedEvent;
import com.loohp.interactionvisualizer.api.events.TileEntityActivatedEvent;
import com.loohp.interactionvisualizer.api.events.TileEntityDeactivatedEvent;
import com.loohp.interactionvisualizer.api.events.TileEntityRemovedEvent;
import com.loohp.interactionvisualizer.objectholders.ChunkPosition;
import com.loohp.interactionvisualizer.objectholders.TileEntity;
import com.loohp.interactionvisualizer.objectholders.TileEntity.TileEntityType;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

public class TileEntityManager implements Listener {

    enum LifecycleChange {
        REMOVED,
        ADDED,
        ACTIVATED,
        DEACTIVATED
    }

    @FunctionalInterface
    interface LifecycleDispatcher<T> {

        void dispatch(T value, LifecycleChange change, TileEntityType type);
    }

    private static final Plugin plugin = InteractionVisualizer.plugin;
    private static final TileEntityType[] tileEntityTypes = TileEntityType.values();
    private static final Map<TileEntityType, Set<Block>> active = new EnumMap<>(TileEntityType.class);
    private static final Map<ChunkPosition, Set<Block>> byChunk = new HashMap<>();
    private static final Map<Block, TileEntityType> lastActiveTypes = new HashMap<>();
    private static final Map<UUID, Set<ChunkPosition>> watchedChunksByPlayer = new HashMap<>();
    private static final Map<ChunkPosition, Integer> watcherCounts = new HashMap<>();
    private static final Set<ChunkPosition> dirtyWatcherChunks = new LinkedHashSet<>();
    private static final Set<ChunkPosition> unloadingChunks = new LinkedHashSet<>();
    private static boolean drainingWatcherChanges;

    public synchronized static void _init_() {
        clearRuntimeState();
        for (TileEntityType type : tileEntityTypes) {
            active.put(type, ConcurrentHashMap.newKeySet());
        }
        TileEntityManager instance = new TileEntityManager();
        Bukkit.getPluginManager().registerEvents(instance, plugin);
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            Bukkit.getPluginManager().registerEvents(new EventDrivenLifecycleListener(instance), plugin);
        }
        Scheduler.runTaskTimer(plugin, () -> {
            if (InteractionVisualizer.eventDrivenBlockUpdates) {
                Set<UUID> online = new LinkedHashSet<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    online.add(player.getUniqueId());
                    updateWatchedChunks(player.getUniqueId(), getAllChunks(player.getLocation()));
                }
                for (UUID playerId : new LinkedHashSet<>(watchedChunksByPlayer.keySet())) {
                    if (!online.contains(playerId)) {
                        clearWatchedChunks(playerId);
                    }
                }
                return;
            }
            for (TileEntityType type : tileEntityTypes) {
                Set<Block> blocks = active.get(type);
                blocks.removeIf(block -> !PlayerLocationManager.hasPlayerNearby(block.getLocation()));
            }
        }, 0, InteractionVisualizerAPI.getGCPeriod());
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (InteractionVisualizer.eventDrivenBlockUpdates) {
                updateWatchedChunks(player.getUniqueId(), getAllChunks(player.getLocation()));
            } else {
                addTileEntities(getAllChunks(player.getLocation()));
            }
        }
    }

    public static Set<Block> getTileEntities(TileEntityType type) {
        Set<Block> set = active.get(type);
        return set != null ? set : new LinkedHashSet<>();
    }

    /**
     * Reconciles loaded chunks after an explicit bulk block mutation performed
     * through the Bukkit block API. Those writes do not emit BlockPlaceEvent or
     * BlockBreakEvent, so waiting for the normal event surface would leave both
     * the legacy and event-driven registries stale.
     *
     * <p>This is an internal main-thread hook for controlled plugin-owned
     * mutations. It never loads a chunk.</p>
     */
    public static void refreshExplicitBlockChanges(Collection<Block> blocks) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Tile-entity reconciliation must run on the Bukkit primary thread");
        }
        Set<ChunkPosition> chunks = new LinkedHashSet<>();
        for (Block block : blocks) {
            if (block != null) {
                chunks.add(getChunk(block.getLocation()));
            }
        }
        addTileEntities(chunks);
    }

    private static Set<ChunkPosition> getAllChunks(Location location) {
        Set<ChunkPosition> chunks = new LinkedHashSet<>();
        World world = location.getWorld();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;

        for (int z = -InteractionVisualizer.tileEntityCheckingRange; z <= InteractionVisualizer.tileEntityCheckingRange; z++) {
            for (int x = -InteractionVisualizer.tileEntityCheckingRange; x <= InteractionVisualizer.tileEntityCheckingRange; x++) {
                chunks.add(new ChunkPosition(world, chunkX + x, chunkZ + z));
            }
        }
        return chunks;
    }

    private static ChunkPosition getChunk(Location location) {
        World world = location.getWorld();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        return new ChunkPosition(world, chunkX, chunkZ);
    }

    private static void addTileEntities(Collection<ChunkPosition> chunks) {
        for (ChunkPosition chunk : chunks) {
            addTileEntities(chunk);
        }
    }

    private synchronized static void addTileEntities(ChunkPosition chunk) {
        if (unloadingChunks.contains(chunk) || !chunk.isLoaded()) {
            return;
        }

        Collection<BlockState> list = chunk.getChunk().getTileEntities(block -> TileEntity.isTileEntityType(block.getType()), false);
        Map<Block, TileEntityType> newBlocks = new LinkedHashMap<>();
        for (BlockState state : list) {
            Block block = state.getBlock();
            TileEntityType type = TileEntity.getTileEntityType(state.getType());
            if (type != null) {
                newBlocks.put(block, type);
            }
        }
        Set<Block> previousGeneration = byChunk.get(chunk);
        if (previousGeneration == null && newBlocks.isEmpty()) {
            return;
        }
        Set<Block> currentGeneration = installPendingIndexGeneration(
                byChunk, unloadingChunks, chunk, newBlocks.keySet());
        if (currentGeneration == null) {
            return;
        }
        Set<Block> reconciliationCandidates = new LinkedHashSet<>(currentGeneration);
        boolean activate = !InteractionVisualizer.eventDrivenBlockUpdates || watcherCounts.getOrDefault(chunk, 0) > 0;
        if (!activate && !deactivateTileEntitiesIfCurrent(chunk, currentGeneration)) {
            return;
        }
        for (Block block : reconciliationCandidates) {
            TileEntityType type = newBlocks.get(block);
            if (!reconcileActiveTypeIfCurrent(byChunk, chunk, currentGeneration,
                    block, type, activate, InteractionVisualizer.eventDrivenBlockUpdates,
                    active, lastActiveTypes, TileEntityManager::dispatchLifecycleChange)) {
                return;
            }
        }
        finishIndexGeneration(byChunk, chunk, currentGeneration, newBlocks.keySet());
    }

    static <K, V> Set<V> installPendingIndexGeneration(
            Map<K, Set<V>> index, K key, Collection<V> currentValues) {
        return installPendingIndexGeneration(index, Set.of(), key, currentValues);
    }

    static <K, V> Set<V> installPendingIndexGeneration(
            Map<K, Set<V>> index, Set<K> blockedKeys, K key, Collection<V> currentValues) {
        if (blockedKeys.contains(key)) {
            return null;
        }
        Set<V> previousGeneration = index.get(key);
        Set<V> generation = new LinkedHashSet<>();
        if (previousGeneration != null) {
            generation.addAll(previousGeneration);
        }
        generation.addAll(currentValues);
        index.put(key, generation);
        return generation;
    }

    static <K, V> boolean finishIndexGeneration(
            Map<K, Set<V>> index, K key, Set<V> generation, Collection<V> currentValues) {
        if (index.get(key) != generation) {
            return false;
        }
        generation.retainAll(currentValues);
        updateNonEmptyIndex(index, key, generation);
        return true;
    }

    static <K, V> void updateNonEmptyIndex(Map<K, Set<V>> index, K key, Set<V> values) {
        if (values.isEmpty() && index.get(key) == values) {
            index.remove(key);
        }
    }

    /** Releases block, chunk and viewer indexes retained by this plugin lifecycle. */
    public synchronized static void shutdown() {
        clearRuntimeState();
    }

    private static void clearRuntimeState() {
        for (Set<Block> blocks : active.values()) {
            blocks.clear();
        }
        active.clear();
        byChunk.clear();
        lastActiveTypes.clear();
        watchedChunksByPlayer.clear();
        watcherCounts.clear();
        dirtyWatcherChunks.clear();
        unloadingChunks.clear();
        drainingWatcherChanges = false;
    }

    /** Number of block, chunk or viewer-index roots retained by this lifecycle. */
    public synchronized static int retainedStateCount() {
        return active.size() + byChunk.size() + lastActiveTypes.size()
                + watchedChunksByPlayer.size() + watcherCounts.size()
                + dirtyWatcherChunks.size() + unloadingChunks.size();
    }

    static <K, T> boolean reconcileActiveTypeIfCurrent(
            Map<K, Set<T>> index, K key, Set<T> indexedValues,
            T value, TileEntityType currentType, boolean activate,
            boolean trackLifecycleEvents,
            Map<TileEntityType, Set<T>> activeByType,
            Map<T, TileEntityType> lastActiveByValue,
            LifecycleDispatcher<T> dispatcher) {
        if (index.get(key) != indexedValues) {
            return false;
        }
        return reconcileActiveType(value, currentType, activate, trackLifecycleEvents,
                activeByType, lastActiveByValue, dispatcher,
                () -> index.get(key) == indexedValues);
    }

    static <T> void reconcileActiveType(T value, TileEntityType currentType, boolean activate,
                                        boolean trackLifecycleEvents,
                                        Map<TileEntityType, Set<T>> activeByType,
                                        Map<T, TileEntityType> lastActiveByValue,
                                        LifecycleDispatcher<T> dispatcher) {
        reconcileActiveType(value, currentType, activate, trackLifecycleEvents,
                activeByType, lastActiveByValue, dispatcher, () -> true);
    }

    private static <T> boolean reconcileActiveType(T value, TileEntityType currentType, boolean activate,
                                                   boolean trackLifecycleEvents,
                                                   Map<TileEntityType, Set<T>> activeByType,
                                                   Map<T, TileEntityType> lastActiveByValue,
                                                   LifecycleDispatcher<T> dispatcher,
                                                   BooleanSupplier stillCurrent) {
        if (!stillCurrent.getAsBoolean()) {
            return false;
        }
        if (currentType == null && trackLifecycleEvents) {
            // Preserve the legacy removal contract for re-entrant listeners:
            // a removed tile is no longer considered last-active when notified.
            lastActiveByValue.remove(value);
        }
        for (TileEntityType type : tileEntityTypes) {
            if (type.equals(currentType)) {
                continue;
            }
            Set<T> values = activeByType.get(type);
            if (values != null && values.remove(value)) {
                dispatcher.dispatch(value, LifecycleChange.REMOVED, type);
                if (!stillCurrent.getAsBoolean()) {
                    return false;
                }
            }
        }

        if (currentType == null) {
            return true;
        }

        if (!stillCurrent.getAsBoolean()) {
            return false;
        }
        Set<T> currentValues = activeByType.get(currentType);
        if (!activate || currentValues == null || !currentValues.add(value) || !trackLifecycleEvents) {
            return true;
        }
        TileEntityType lastActiveType = lastActiveByValue.put(value, currentType);
        dispatcher.dispatch(value, currentType.equals(lastActiveType)
                ? LifecycleChange.ACTIVATED : LifecycleChange.ADDED, currentType);
        return stillCurrent.getAsBoolean();
    }

    private static void dispatchLifecycleChange(Block block, LifecycleChange change, TileEntityType type) {
        switch (change) {
            case REMOVED -> Bukkit.getPluginManager().callEvent(new TileEntityRemovedEvent(block, type));
            case ADDED -> callAddedEvent(block, type);
            case ACTIVATED -> callActivatedEvent(block, type);
            case DEACTIVATED -> callDeactivatedEvent(block, type);
        }
    }

    private synchronized static void deactivateTileEntities(ChunkPosition chunk) {
        if (unloadingChunks.contains(chunk)) {
            return;
        }
        Set<Block> indexedBlocks = byChunk.get(chunk);
        if (indexedBlocks == null || indexedBlocks.isEmpty()) {
            return;
        }
        Set<Block> currentGeneration = installPendingIndexGeneration(byChunk, chunk, indexedBlocks);
        deactivateTileEntitiesIfCurrent(chunk, currentGeneration);
    }

    private static boolean deactivateTileEntitiesIfCurrent(ChunkPosition chunk, Set<Block> currentGeneration) {
        return deactivateIfCurrent(
                byChunk, chunk, currentGeneration, active, TileEntityManager::dispatchLifecycleChange);
    }

    static <K, T> boolean deactivateIfCurrent(
            Map<K, Set<T>> index, K key, Set<T> currentGeneration,
            Map<TileEntityType, Set<T>> activeByType,
            LifecycleDispatcher<T> dispatcher) {
        for (T block : currentGeneration) {
            if (index.get(key) != currentGeneration) {
                return false;
            }
            for (TileEntityType type : tileEntityTypes) {
                Set<T> values = activeByType.get(type);
                if (values != null && values.remove(block)) {
                    dispatcher.dispatch(block, LifecycleChange.DEACTIVATED, type);
                    if (index.get(key) != currentGeneration) {
                        return false;
                    }
                }
            }
        }
        return index.get(key) == currentGeneration;
    }

    private synchronized static void unloadTileEntities(ChunkPosition chunk) {
        if (!unloadingChunks.add(chunk)) {
            return;
        }
        Set<Block> indexedBlocks = byChunk.get(chunk);
        if (indexedBlocks == null) {
            return;
        }
        Set<Block> currentGeneration = installPendingIndexGeneration(byChunk, chunk, indexedBlocks);
        if (!deactivateTileEntitiesIfCurrent(chunk, currentGeneration)
                || !byChunk.remove(chunk, currentGeneration)) {
            return;
        }
        for (Block block : currentGeneration) {
            lastActiveTypes.remove(block);
        }
    }

    private synchronized static void finishChunkUnload(ChunkPosition chunk) {
        if (!unloadingChunks.remove(chunk)) {
            return;
        }
        if (chunk.isLoaded() && (!InteractionVisualizer.eventDrivenBlockUpdates
                || watcherCounts.getOrDefault(chunk, 0) > 0)) {
            addTileEntities(chunk);
        }
    }

    private synchronized static void updateWatchedChunks(UUID playerId, Set<ChunkPosition> nextChunks) {
        commitWatcherUpdate(
                watchedChunksByPlayer, watcherCounts, dirtyWatcherChunks,
                playerId, nextChunks);
        drainWatcherChanges();
    }

    private synchronized static void clearWatchedChunks(UUID playerId) {
        if (!watchedChunksByPlayer.containsKey(playerId)) {
            return;
        }
        updateWatchedChunks(playerId, Set.of());
    }

    static <P, K> void commitWatcherUpdate(
            Map<P, Set<K>> watchedByPlayer, Map<K, Integer> counts, Set<K> dirty,
            P playerId, Collection<K> requestedChunks) {
        Set<K> nextChunks = new LinkedHashSet<>(requestedChunks);
        Set<K> previousChunks = nextChunks.isEmpty()
                ? watchedByPlayer.remove(playerId)
                : watchedByPlayer.put(playerId, nextChunks);
        if (previousChunks == null) {
            previousChunks = Set.of();
        }

        for (K chunk : previousChunks) {
            if (nextChunks.contains(chunk)) {
                continue;
            }
            int count = counts.getOrDefault(chunk, 0);
            if (count <= 1) {
                counts.remove(chunk);
                dirty.add(chunk);
            } else {
                counts.put(chunk, count - 1);
            }
        }
        for (K chunk : nextChunks) {
            if (previousChunks.contains(chunk)) {
                continue;
            }
            int count = counts.getOrDefault(chunk, 0);
            counts.put(chunk, count + 1);
            if (count == 0) {
                dirty.add(chunk);
            }
        }
    }

    private static void drainWatcherChanges() {
        if (drainingWatcherChanges) {
            return;
        }
        drainingWatcherChanges = true;
        try {
            while (!dirtyWatcherChunks.isEmpty()) {
                Iterator<ChunkPosition> iterator = dirtyWatcherChunks.iterator();
                ChunkPosition chunk = iterator.next();
                iterator.remove();
                if (unloadingChunks.contains(chunk)) {
                    continue;
                }
                if (watcherCounts.getOrDefault(chunk, 0) > 0) {
                    addTileEntities(chunk);
                } else {
                    deactivateTileEntities(chunk);
                }
            }
        } finally {
            drainingWatcherChanges = false;
        }
    }

    private static void callAddedEvent(Block block, TileEntityType type) {
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            Bukkit.getPluginManager().callEvent(new TileEntityAddedEvent(block, type));
        }
    }

    private static void callActivatedEvent(Block block, TileEntityType type) {
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            Bukkit.getPluginManager().callEvent(new TileEntityActivatedEvent(block, type));
        }
    }

    private static void callDeactivatedEvent(Block block, TileEntityType type) {
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            Bukkit.getPluginManager().callEvent(new TileEntityDeactivatedEvent(block, type));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            updateWatchedChunks(event.getPlayer().getUniqueId(), getAllChunks(event.getPlayer().getLocation()));
        } else {
            addTileEntities(getAllChunks(event.getPlayer().getLocation()));
        }
    }

    public void onQuit(PlayerQuitEvent event) {
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            clearWatchedChunks(event.getPlayer().getUniqueId());
        }
    }

    public void onRespawn(PlayerRespawnEvent event) {
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            updateWatchedChunks(event.getPlayer().getUniqueId(), getAllChunks(event.getRespawnLocation()));
        }
    }

    public void onChangedWorld(PlayerChangedWorldEvent event) {
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            updateWatchedChunks(event.getPlayer().getUniqueId(), getAllChunks(event.getPlayer().getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block != null) {
            TileEntityType type = TileEntity.getTileEntityType(block.getType());
            if (type != null) {
                if (InteractionVisualizer.eventDrivenBlockUpdates) {
                    Set<ChunkPosition> chunks = getAllChunks(event.getPlayer().getLocation());
                    chunks.add(getChunk(block.getLocation()));
                    updateWatchedChunks(event.getPlayer().getUniqueId(), chunks);
                }
                if (!active.get(type).contains(block)) {
                    addTileEntities(getChunk(block.getLocation()));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (!from.getWorld().equals(to.getWorld()) || from.getBlockX() >> 4 != to.getBlockX() >> 4 || from.getBlockZ() >> 4 != to.getBlockZ() >> 4) {
            if (InteractionVisualizer.eventDrivenBlockUpdates) {
                updateWatchedChunks(event.getPlayer().getUniqueId(), getAllChunks(to));
            } else {
                addTileEntities(getAllChunks(to));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (!from.getWorld().equals(to.getWorld())) {
            if (InteractionVisualizer.eventDrivenBlockUpdates) {
                updateWatchedChunks(event.getPlayer().getUniqueId(), getAllChunks(to));
            } else {
                addTileEntities(getAllChunks(to));
            }
        } else if (from.getBlockX() >> 4 != to.getBlockX() >> 4 || from.getBlockZ() >> 4 != to.getBlockZ() >> 4) {
            if (!isMovingTooFast(event.getPlayer(), from, to)) {
                if (InteractionVisualizer.eventDrivenBlockUpdates) {
                    updateWatchedChunks(event.getPlayer().getUniqueId(), getAllChunks(to));
                } else {
                    addTileEntities(getAllChunks(to));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (event.getVehicle().getPassengers().stream().anyMatch(each -> each instanceof Player)) {
            Location from = event.getFrom();
            Location to = event.getTo();
            boolean changedWorld = !from.getWorld().equals(to.getWorld());
            boolean changedChunk = from.getBlockX() >> 4 != to.getBlockX() >> 4 || from.getBlockZ() >> 4 != to.getBlockZ() >> 4;
            if (changedWorld || changedChunk) {
                if (InteractionVisualizer.eventDrivenBlockUpdates) {
                    boolean movingTooFast = !changedWorld && isMovingTooFast(null, from, to);
                    if (!movingTooFast) {
                        Set<ChunkPosition> chunks = getAllChunks(to);
                        for (org.bukkit.entity.Entity passenger : event.getVehicle().getPassengers()) {
                            if (passenger instanceof Player player) {
                                updateWatchedChunks(player.getUniqueId(), chunks);
                            }
                        }
                    }
                } else if (changedWorld || !isMovingTooFast(null, from, to)) {
                    addTileEntities(getAllChunks(to));
                }
            }
        }
    }

    public void onChunkLoad(ChunkLoadEvent event) {
        if (!InteractionVisualizer.eventDrivenBlockUpdates) {
            return;
        }
        ChunkPosition chunk = new ChunkPosition(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
        unloadingChunks.remove(chunk);
        if (watcherCounts.getOrDefault(chunk, 0) > 0) {
            addTileEntities(chunk);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        ChunkPosition chunk = new ChunkPosition(
                event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
        unloadTileEntities(chunk);
        Scheduler.runTask(plugin, () -> finishChunkUnload(chunk));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakBlock(BlockBreakEvent event) {
        if (TileEntity.isTileEntityType(event.getBlock().getType())) {
            Scheduler.runTaskLater(plugin, () -> addTileEntities(getChunk(event.getBlock().getLocation())), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceBlock(BlockPlaceEvent event) {
        if (TileEntity.isTileEntityType(event.getBlock().getType())) {
            Scheduler.runTaskLater(plugin, () -> addTileEntities(getChunk(event.getBlock().getLocation())), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        Set<ChunkPosition> chunks = new LinkedHashSet<>();
        if (TileEntity.isTileEntityType(event.getBlock().getType())) {
            chunks.add(getChunk(event.getBlock().getLocation()));
        }
        for (Block block : event.blockList()) {
            if (TileEntity.isTileEntityType(block.getType())) {
                chunks.add(getChunk(block.getLocation()));
            }
        }
        Scheduler.runTaskLater(plugin, () -> addTileEntities(chunks), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Set<ChunkPosition> chunks = new LinkedHashSet<>();
        for (Block block : event.blockList()) {
            if (TileEntity.isTileEntityType(block.getType())) {
                chunks.add(getChunk(block.getLocation()));
            }
        }
        Scheduler.runTaskLater(plugin, () -> addTileEntities(chunks), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (TileEntity.isTileEntityType(event.getBlock().getType())) {
            Scheduler.runTaskLater(plugin, () -> addTileEntities(getChunk(event.getBlock().getLocation())), 1);
        }
    }

    private static final class EventDrivenLifecycleListener implements Listener {

        private final TileEntityManager manager;

        private EventDrivenLifecycleListener(TileEntityManager manager) {
            this.manager = manager;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            manager.onQuit(event);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onRespawn(PlayerRespawnEvent event) {
            manager.onRespawn(event);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onChangedWorld(PlayerChangedWorldEvent event) {
            manager.onChangedWorld(event);
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onChunkLoad(ChunkLoadEvent event) {
            manager.onChunkLoad(event);
        }

    }

    private boolean isMovingTooFast(Player player, Location from, Location to) {
        double changeX = Math.abs(from.getX() - to.getX());
        double changeZ = Math.abs(from.getZ() - to.getZ());
        double horizontalDistanceSquared = changeX * changeX + changeZ * changeZ;
        if (player != null && player.isGliding()) {
            return horizontalDistanceSquared > InteractionVisualizer.ignoreGlideSquared;
        }
        if (player != null && player.isFlying()) {
            return horizontalDistanceSquared > InteractionVisualizer.ignoreFlySquared;
        }
        return horizontalDistanceSquared > InteractionVisualizer.ignoreWalkSquared;
    }

}
