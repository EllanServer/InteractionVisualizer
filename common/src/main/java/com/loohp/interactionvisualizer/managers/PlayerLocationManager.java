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
import com.loohp.interactionvisualizer.entityholders.VisualizerEntity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

public class PlayerLocationManager {

    private static final ConcurrentMap<UUID, PlayerChunkSnapshot> PLAYER_CHUNKS = new ConcurrentHashMap<>();

    public static boolean hasPlayerNearby(Location location, double range, boolean eyeLocation, Predicate<Player> predicate) {
        World world = location.getWorld();
        double rangeSquared = range * range;
        double targetX = location.getX();
        double targetY = location.getY();
        double targetZ = location.getZ();
        for (Player player : world.getPlayers()) {
            if (!predicate.test(player)) {
                continue;
            }
            double deltaX = player.getX() - targetX;
            double deltaY = player.getY() + (eyeLocation ? player.getEyeHeight() : 0.0) - targetY;
            double deltaZ = player.getZ() - targetZ;
            if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= rangeSquared) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasPlayerNearby(Location location) {
        World world = location.getWorld();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return false;
        }

        int checkingRange = InteractionVisualizer.tileEntityCheckingRange;
        return checkingRange >= 0 && getPlayerChunkSnapshot(world).hasPlayerWithin(chunkX, chunkZ, checkingRange);
    }

    public static Location getPlayerLocation(Player player) {
        return player.getLocation();
    }

    public static Location getPlayerEyeLocation(Player player) {
        return player.getEyeLocation();
    }

    /** Releases per-world snapshots retained by the previous plugin lifecycle. */
    public static void clearCache() {
        PLAYER_CHUNKS.clear();
    }

    public static int retainedStateCount() {
        return PLAYER_CHUNKS.size();
    }

    public static Collection<Player> filterOutOfRange(Collection<Player> players, VisualizerEntity entity) {
        return filterOutOfRange(players, entity.getLocation());
    }

    public static Collection<Player> filterOutOfRange(Collection<Player> players, Entity entity) {
        return filterOutOfRange(players, entity.getLocation());
    }

    public static Collection<Player> filterOutOfRange(Collection<Player> players, Location location) {
        return filterOutOfRange(players, location, player -> true);
    }

    public static Collection<Player> filterOutOfRange(Collection<Player> players, Location location, Predicate<Player> predicate) {
        Collection<Player> playersInRange = new HashSet<>(hashSetCapacity(players.size()));
        int range = InteractionVisualizer.playerTrackingRange.getOrDefault(location.getWorld(), 64);
        double rangeSquared = (double) range * range;
        World world = location.getWorld();
        double targetX = location.getX();
        double targetY = location.getY();
        double targetZ = location.getZ();
        for (Player player : players) {
            if (!player.getWorld().equals(world)) {
                continue;
            }
            double deltaX = player.getX() - targetX;
            double deltaY = player.getY() - targetY;
            double deltaZ = player.getZ() - targetZ;
            if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= rangeSquared && predicate.test(player)) {
                playersInRange.add(player);
            }
        }
        return playersInRange;
    }

    private static PlayerChunkSnapshot getPlayerChunkSnapshot(World world) {
        UUID worldId = world.getUID();
        int currentTick = Bukkit.getCurrentTick();
        PlayerChunkSnapshot snapshot = PLAYER_CHUNKS.get(worldId);
        if (snapshot != null && snapshot.tick == currentTick) {
            return snapshot;
        }
        return PLAYER_CHUNKS.compute(worldId, (ignored, current) -> {
            int buildTick = Bukkit.getCurrentTick();
            if (current != null && current.tick == buildTick) {
                return current;
            }
            return PlayerChunkSnapshot.capture(world, buildTick);
        });
    }

    private static int hashSetCapacity(int expectedSize) {
        if (expectedSize < 3) {
            return expectedSize + 1;
        }
        return expectedSize < (1 << 30) ? (int) (expectedSize / 0.75F) + 1 : Integer.MAX_VALUE;
    }

    /**
     * One immutable player-chunk index per world and server tick. The hot block
     * render loops can share it instead of rebuilding a square of chunk objects
     * and rescanning every player for every tile entity.
     */
    private static final class PlayerChunkSnapshot {

        private final int tick;
        private final long[] chunks;

        private PlayerChunkSnapshot(int tick, long[] chunks) {
            this.tick = tick;
            this.chunks = chunks;
        }

        private static PlayerChunkSnapshot capture(World world, int tick) {
            Collection<Player> players = world.getPlayers();
            long[] chunks = new long[players.size()];
            int index = 0;
            for (Player player : players) {
                int chunkX = Location.locToBlock(player.getX()) >> 4;
                int chunkZ = Location.locToBlock(player.getZ()) >> 4;
                chunks[index++] = pack(chunkX, chunkZ);
            }
            if (index != chunks.length) {
                chunks = Arrays.copyOf(chunks, index);
            }
            Arrays.sort(chunks);
            return new PlayerChunkSnapshot(tick, chunks);
        }

        private boolean hasPlayerWithin(int chunkX, int chunkZ, int range) {
            if (chunks.length == 0) {
                return false;
            }

            long diameter = (long) range * 2L + 1L;
            int comparisonsPerLookup = Integer.SIZE - Integer.numberOfLeadingZeros(chunks.length);
            boolean binaryLookup = diameter <= Integer.MAX_VALUE
                    && diameter * diameter <= (long) chunks.length / Math.max(1, comparisonsPerLookup);
            if (binaryLookup) {
                for (int deltaX = -range; deltaX <= range; deltaX++) {
                    for (int deltaZ = -range; deltaZ <= range; deltaZ++) {
                        if (Arrays.binarySearch(chunks, pack(chunkX + deltaX, chunkZ + deltaZ)) >= 0) {
                            return true;
                        }
                    }
                }
                return false;
            }

            for (long packed : chunks) {
                long deltaX = (long) unpackX(packed) - chunkX;
                long deltaZ = (long) unpackZ(packed) - chunkZ;
                if (Math.abs(deltaX) <= range && Math.abs(deltaZ) <= range) {
                    return true;
                }
            }
            return false;
        }

        private static long pack(int chunkX, int chunkZ) {
            return (long) chunkX << Integer.SIZE | chunkZ & 0xFFFFFFFFL;
        }

        private static int unpackX(long packed) {
            return (int) (packed >> Integer.SIZE);
        }

        private static int unpackZ(long packed) {
            return (int) packed;
        }
    }

}
