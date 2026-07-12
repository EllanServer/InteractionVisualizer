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

public class TileEntityManager implements Listener {

    private static final Plugin plugin = InteractionVisualizer.plugin;
    private static final TileEntityType[] tileEntityTypes = TileEntityType.values();
    private static final Map<TileEntityType, Set<Block>> active = new EnumMap<>(TileEntityType.class);
    private static final Map<ChunkPosition, Set<Block>> byChunk = new HashMap<>();
    private static final Map<Block, TileEntityType> lastActiveTypes = new HashMap<>();
    private static final Map<UUID, Set<ChunkPosition>> watchedChunksByPlayer = new HashMap<>();
    private static final Map<ChunkPosition, Integer> watcherCounts = new HashMap<>();

    public static void _init_() {
        for (TileEntityType type : tileEntityTypes) {
            active.put(type, ConcurrentHashMap.newKeySet());
        }
        TileEntityManager instance = new TileEntityManager();
        Bukkit.getPluginManager().registerEvents(instance, plugin);
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
        if (!chunk.isLoaded()) {
            return;
        }

        Collection<BlockState> list = chunk.getChunk().getTileEntities(block -> TileEntity.isTileEntityType(block.getType()), false);
        Set<Block> blocks = byChunk.get(chunk);
        if (blocks == null) {
            blocks = new LinkedHashSet<>();
            byChunk.put(chunk, blocks);
        }
        boolean activate = !InteractionVisualizer.eventDrivenBlockUpdates || watcherCounts.getOrDefault(chunk, 0) > 0;
        Map<Block, TileEntityType> newBlocks = new LinkedHashMap<>();
        for (BlockState state : list) {
            Block block = state.getBlock();
            TileEntityType type = TileEntity.getTileEntityType(state.getType());
            if (type != null) {
                if (activate && active.get(type).add(block)) {
                    if (InteractionVisualizer.eventDrivenBlockUpdates) {
                        TileEntityType lastActiveType = lastActiveTypes.put(block, type);
                        if (type.equals(lastActiveType)) {
                            callActivatedEvent(block, type);
                        } else {
                            callAddedEvent(block, type);
                        }
                    }
                }
                newBlocks.put(block, type);
                blocks.add(block);
            }
        }
        Iterator<Block> itr = blocks.iterator();
        while (itr.hasNext()) {
            Block block = itr.next();
            TileEntityType type = newBlocks.get(block);
            if (type == null) {
                itr.remove();
                if (InteractionVisualizer.eventDrivenBlockUpdates) {
                    lastActiveTypes.remove(block);
                }
                for (TileEntityType t : tileEntityTypes) {
                    if (active.get(t).remove(block)) {
                        Bukkit.getPluginManager().callEvent(new TileEntityRemovedEvent(block, t));
                    }
                }
            } else {
                for (TileEntityType t : tileEntityTypes) {
                    if (!t.equals(type)) {
                        if (active.get(t).remove(block)) {
                            Bukkit.getPluginManager().callEvent(new TileEntityRemovedEvent(block, t));
                        }
                    }
                }
            }
        }
    }

    private synchronized static void deactivateTileEntities(ChunkPosition chunk) {
        Set<Block> blocks = byChunk.get(chunk);
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        for (Block block : blocks) {
            for (TileEntityType type : tileEntityTypes) {
                if (active.get(type).remove(block)) {
                    callDeactivatedEvent(block, type);
                }
            }
        }
    }

    private synchronized static void unloadTileEntities(ChunkPosition chunk) {
        deactivateTileEntities(chunk);
        Set<Block> blocks = byChunk.remove(chunk);
        if (blocks != null) {
            for (Block block : blocks) {
                lastActiveTypes.remove(block);
            }
        }
    }

    private synchronized static void updateWatchedChunks(UUID playerId, Set<ChunkPosition> nextChunks) {
        Set<ChunkPosition> previousChunks = watchedChunksByPlayer.put(playerId, nextChunks);
        if (previousChunks == null) {
            previousChunks = Set.of();
        }

        for (ChunkPosition chunk : previousChunks) {
            if (nextChunks.contains(chunk)) {
                continue;
            }
            Integer count = watcherCounts.get(chunk);
            if (count == null || count <= 1) {
                watcherCounts.remove(chunk);
                deactivateTileEntities(chunk);
            } else {
                watcherCounts.put(chunk, count - 1);
            }
        }

        for (ChunkPosition chunk : nextChunks) {
            if (previousChunks.contains(chunk)) {
                continue;
            }
            int count = watcherCounts.getOrDefault(chunk, 0);
            watcherCounts.put(chunk, count + 1);
            if (count == 0) {
                addTileEntities(chunk);
            }
        }
    }

    private synchronized static void clearWatchedChunks(UUID playerId) {
        Set<ChunkPosition> chunks = watchedChunksByPlayer.remove(playerId);
        if (chunks == null) {
            return;
        }
        for (ChunkPosition chunk : chunks) {
            Integer count = watcherCounts.get(chunk);
            if (count == null || count <= 1) {
                watcherCounts.remove(chunk);
                deactivateTileEntities(chunk);
            } else {
                watcherCounts.put(chunk, count - 1);
            }
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            clearWatchedChunks(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        if (InteractionVisualizer.eventDrivenBlockUpdates) {
            updateWatchedChunks(event.getPlayer().getUniqueId(), getAllChunks(event.getRespawnLocation()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!InteractionVisualizer.eventDrivenBlockUpdates) {
            return;
        }
        ChunkPosition chunk = new ChunkPosition(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
        if (watcherCounts.getOrDefault(chunk, 0) > 0) {
            addTileEntities(chunk);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (!InteractionVisualizer.eventDrivenBlockUpdates) {
            return;
        }
        unloadTileEntities(new ChunkPosition(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ()));
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
