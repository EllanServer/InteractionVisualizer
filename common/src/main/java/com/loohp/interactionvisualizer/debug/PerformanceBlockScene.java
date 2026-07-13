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

package com.loohp.interactionvisualizer.debug;

import com.loohp.interactionvisualizer.InteractionVisualizer;
import com.loohp.interactionvisualizer.managers.TileEntityManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Furnace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Permission-gated, reversible block workload for event-driven display A/B tests.
 *
 * <p>This class deliberately has no command or permission surface. The caller must
 * keep it behind the existing {@code interactionvisualizer.performance} gate. All
 * entry points fail when called off the Bukkit primary thread. A scene only claims
 * already-loaded air blocks and tags every claimed tile entity with a session
 * marker. Cleanup restores a captured {@link BlockState} only while that marker is
 * still present; an externally replaced block is never overwritten. Replacing an
 * arbitrary live container cannot be made ownership-safe, so the footprint is
 * intentionally restricted to air (fail closed). Inventory snapshots are retained
 * by the restoration model defensively, but this policy never claims a pre-existing
 * container.</p>
 */
public final class PerformanceBlockScene {

    public static final int MAX_BLOCKS = 4_096;

    private static final int SEARCH_PLANES = 8;
    private static final String ENABLE_PROPERTY = "interactionvisualizer.performance.allowBlockScene";
    private static final String OWNER_MARKER = "performance_block_scene";
    private static final Material[] MATERIAL_PATTERN = {
            Material.FURNACE,
            Material.BLAST_FURNACE,
            Material.SMOKER,
            Material.BEEHIVE,
            Material.BEE_NEST
    };
    private static final Map<UUID, Session> scenes = new HashMap<>();

    private static long nextRevision;

    private PerformanceBlockScene() {
    }

    /** Workload behavior after creation. */
    public enum Mode {
        /** Static mixed furnaces and bee blocks. Furnace inputs have no fuel. */
        IDLE,
        /** Furnaces are primed so vanilla ticks emit real furnace events. */
        ACTIVE,
        /** Mutations use BlockState/inventory writes and intentionally emit no Bukkit event. */
        DIRECT_WRITE;

        public static Mode parse(String value) {
            Objects.requireNonNull(value, "value");
            return switch (value.toLowerCase(Locale.ROOT).replace('_', '-')) {
                case "idle" -> IDLE;
                case "active" -> ACTIVE;
                case "direct", "direct-write" -> DIRECT_WRITE;
                default -> throw new IllegalArgumentException("Unknown performance block mode: " + value);
            };
        }
    }

    public enum SceneState {
        ABSENT,
        READY,
        CLEARED,
        PARTIAL_CLEAR
    }

    /**
     * Stable, assertion-friendly scene state. Counts describe the original scene;
     * {@code ownedCount} describes blocks that still carry this session's marker.
     */
    public record Snapshot(
            UUID ownerId,
            SceneState state,
            Mode mode,
            int requestedCount,
            int placedCount,
            int remainingCount,
            int unresolvedCount,
            int ownedCount,
            int unloadedCount,
            int furnaceCount,
            int blastFurnaceCount,
            int smokerCount,
            int beeHiveCount,
            int beeNestCount,
            long revision,
            int lastMutationRequested,
            int lastMutationApplied,
            int restoredCount,
            int skippedExternalCount,
            int restoreFailureCount,
            int inspectionFailureCount,
            String detail
    ) {

        public int eventEligibleFurnaceCount() {
            return furnaceCount + blastFurnaceCount + smokerCount;
        }

        public String summary() {
            return "state=" + state.name().toLowerCase(Locale.ROOT)
                    + " mode=" + (mode == null ? "none" : mode.name().toLowerCase(Locale.ROOT))
                    + " requested=" + requestedCount
                    + " placed=" + placedCount
                    + " owned=" + ownedCount
                    + " furnace=" + furnaceCount
                    + " blastFurnace=" + blastFurnaceCount
                    + " smoker=" + smokerCount
                    + " beeHive=" + beeHiveCount
                    + " beeNest=" + beeNestCount
                    + " eventEligibleFurnaces=" + eventEligibleFurnaceCount()
                    + " revision=" + revision
                    + " mutationRequested=" + lastMutationRequested
                    + " mutationApplied=" + lastMutationApplied
                    + " restored=" + restoredCount
                    + " skippedExternal=" + skippedExternalCount
                    + " restoreFailures=" + restoreFailureCount
                    + " remaining=" + remainingCount
                    + " unresolved=" + unresolvedCount
                    + " unloaded=" + unloadedCount
                    + " inspectionFailures=" + inspectionFailureCount
                    + " detail=" + detail;
        }
    }

    /** Result of the no-throw, best-effort disable cleanup. */
    public record ShutdownReport(
            int trackedCount,
            int restoredCount,
            int unloadedCount,
            int ownershipMismatchCount,
            int restoreFailureCount,
            int inspectionFailureCount
    ) {

        public int unresolvedCount() {
            return unloadedCount + ownershipMismatchCount + restoreFailureCount + inspectionFailureCount;
        }

        public String summary() {
            return "tracked=" + trackedCount
                    + " restored=" + restoredCount
                    + " unresolved=" + unresolvedCount()
                    + " unloaded=" + unloadedCount
                    + " ownershipMismatch=" + ownershipMismatchCount
                    + " restoreFailures=" + restoreFailureCount
                    + " inspectionFailures=" + inspectionFailureCount;
        }
    }

    /**
     * Creates an exact-size scene, clamped to {@link #MAX_BLOCKS}. Preflight only
     * selects loaded air blocks; insufficient safe capacity aborts before writes.
     */
    public static Snapshot create(Player owner, int requestedCount, Mode mode) {
        requirePrimaryThread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(mode, "mode");
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            throw new IllegalStateException("Block performance scenes are disabled; use a disposable test server "
                    + "started with -D" + ENABLE_PROPERTY + "=true");
        }

        Snapshot previous = clear(owner);
        if (previous.state() == SceneState.PARTIAL_CLEAR) {
            throw new IllegalStateException("Previous performance block scene could not be fully restored: "
                    + previous.summary());
        }

        int count = Math.max(1, Math.min(MAX_BLOCKS, requestedCount));
        NamespacedKey markerKey = markerKey();
        long revision = ++nextRevision;
        String markerValue = owner.getUniqueId() + ":" + revision;
        List<Entry> entries = plan(owner, count);
        Counts counts = Counts.of(entries);
        Session session = new Session(owner.getUniqueId(), requestedCount, count, mode, revision,
                markerKey, markerValue, entries, counts);

        List<Entry> installed = new ArrayList<>(entries.size());
        try {
            for (Entry entry : entries) {
                if (entry.block.getType() != entry.originalState.getType()
                        || !entry.block.getType().isAir()) {
                    throw new IllegalStateException("Safe scene footprint changed during creation at "
                            + coordinate(entry.block));
                }
                installed.add(entry);
                install(session, entry, mode == Mode.ACTIVE ? Mode.ACTIVE : Mode.IDLE);
            }
        } catch (RuntimeException exception) {
            RollbackResult rollback = rollback(session, installed);
            if (!rollback.unresolvedEntries().isEmpty()) {
                session.entries.clear();
                session.entries.addAll(rollback.unresolvedEntries());
                session.cleanupPending = true;
                session.restoredCount = rollback.restoredCount();
                session.skippedExternalCount = rollback.ownershipMismatchCount();
                session.restoreFailureCount = rollback.restoreFailureCount();
                session.inspectionFailureCount = rollback.inspectionFailureCount();
                scenes.put(owner.getUniqueId(), session);
                exception.addSuppressed(new IllegalStateException(
                        rollback.unresolvedEntries().size()
                                + " installed scene blocks remain unresolved after rollback (unloaded="
                                + rollback.unloadedCount() + ", ownershipMismatch="
                                + rollback.ownershipMismatchCount() + ", restoreFailures="
                                + rollback.restoreFailureCount() + ", inspectionFailures="
                                + rollback.inspectionFailureCount() + ")"));
            }
            throw exception;
        }

        scenes.put(owner.getUniqueId(), session);
        TileEntityManager.refreshExplicitBlockChanges(blocks(entries));
        return snapshot(session, SceneState.READY, "created");
    }

    /** Mutates up to {@code requestedOperations} entries using the current mode. */
    public static Snapshot mutate(Player owner, int requestedOperations) {
        Objects.requireNonNull(owner, "owner");
        return mutate(owner.getUniqueId(), requestedOperations);
    }

    /** UUID variant for scenes whose creating player is no longer online. */
    public static Snapshot mutate(UUID ownerId, int requestedOperations) {
        requirePrimaryThread();
        Session session = requireSession(ownerId);
        return mutate(session, requestedOperations, session.mode);
    }

    /** Changes mode and mutates one pass over the scene. */
    public static Snapshot mutate(Player owner, Mode mode) {
        Objects.requireNonNull(owner, "owner");
        return mutate(owner.getUniqueId(), mode);
    }

    /** UUID variant for scenes whose creating player is no longer online. */
    public static Snapshot mutate(UUID ownerId, Mode mode) {
        requirePrimaryThread();
        Session session = requireSession(ownerId);
        return mutate(session, session.entries.size(), mode);
    }

    /** Changes mode and mutates up to {@code requestedOperations} entries. */
    public static Snapshot mutate(Player owner, int requestedOperations, Mode mode) {
        Objects.requireNonNull(owner, "owner");
        return mutate(owner.getUniqueId(), requestedOperations, mode);
    }

    /** UUID variant for scenes whose creating player is no longer online. */
    public static Snapshot mutate(UUID ownerId, int requestedOperations, Mode mode) {
        requirePrimaryThread();
        Session session = requireSession(ownerId);
        return mutate(session, requestedOperations, mode);
    }

    /**
     * Restores every block still owned by the scene. Blocks whose ownership marker
     * disappeared are counted and left untouched. Actual restoration failures stay
     * registered so a later call can retry them.
     */
    public static Snapshot clear(Player owner) {
        Objects.requireNonNull(owner, "owner");
        return clear(owner.getUniqueId());
    }

    /** UUID variant that never requires an {@code OfflinePlayer} or loads a world/chunk. */
    public static Snapshot clear(UUID ownerId) {
        requirePrimaryThread();
        Objects.requireNonNull(ownerId, "ownerId");
        Session session = scenes.get(ownerId);
        if (session == null) {
            return absent(ownerId, "no_scene");
        }

        int restored = 0;
        int skipped = 0;
        int restoreFailures = 0;
        int inspectionFailures = 0;
        List<Entry> affectedEntries = new ArrayList<>(session.entries);
        List<Entry> restoredEntries = new ArrayList<>();
        List<Entry> unresolved = new ArrayList<>();
        for (Entry entry : affectedEntries) {
            if (!isLoaded(entry)) {
                unresolved.add(entry);
                continue;
            }
            OwnershipStatus ownership = ownershipStatus(session, entry);
            if (ownership != OwnershipStatus.OWNED) {
                if (ownership == OwnershipStatus.NOT_OWNED) {
                    skipped++;
                } else {
                    inspectionFailures++;
                }
                unresolved.add(entry);
            } else if (restore(entry)) {
                restored++;
                restoredEntries.add(entry);
            } else {
                restoreFailures++;
                unresolved.add(entry);
            }
        }

        session.restoredCount += restored;
        session.skippedExternalCount = skipped;
        session.restoreFailureCount = restoreFailures;
        session.inspectionFailureCount = inspectionFailures;
        session.entries.clear();
        session.entries.addAll(unresolved);
        session.cleanupPending = !unresolved.isEmpty();
        boolean cleared = unresolved.isEmpty();
        if (cleared) {
            scenes.remove(ownerId, session);
        }
        if (!restoredEntries.isEmpty()) {
            TileEntityManager.refreshExplicitBlockChanges(blocks(restoredEntries));
        }
        return cleared
                ? snapshot(session, SceneState.CLEARED,
                        skipped == 0 ? "cleared" : "cleared_with_external_changes")
                : snapshot(session, SceneState.PARTIAL_CLEAR, "restore_retry_required");
    }

    public static Snapshot snapshot(Player owner) {
        Objects.requireNonNull(owner, "owner");
        return snapshot(owner.getUniqueId());
    }

    /** UUID variant for status checks after the creating player disconnects. */
    public static Snapshot snapshot(UUID ownerId) {
        requirePrimaryThread();
        Objects.requireNonNull(ownerId, "ownerId");
        Session session = scenes.get(ownerId);
        return session == null ? absent(ownerId, "no_scene")
                : snapshot(session, !session.cleanupPending
                ? SceneState.READY : SceneState.PARTIAL_CLEAR, "snapshot");
    }

    public static String status(Player owner) {
        return snapshot(owner).summary();
    }

    public static String status(UUID ownerId) {
        return snapshot(ownerId).summary();
    }

    /** Snapshot of owner ids only; this does not resolve players or load worlds/chunks. */
    public static List<UUID> activeOwnerIds() {
        requirePrimaryThread();
        return List.copyOf(scenes.keySet());
    }

    /**
     * Best-effort disable cleanup. Only blocks that still carry the exact
     * session marker are restored; externally replaced blocks remain untouched.
     *
     * <p>This method never loads chunks. It also deliberately drops its in-memory
     * sessions after the report is assembled because plugin disable makes retrying
     * impossible; every block that was not proven owned and restored is reported as
     * unresolved instead of being presented as a successful cleanup.</p>
     *
     * @return categorized cleanup report; this method is best-effort and does not throw
     */
    public static ShutdownReport shutdown() {
        int tracked = 0;
        int restored = 0;
        int unloaded = 0;
        int ownershipMismatch = 0;
        int restoreFailures = 0;
        int inspectionFailures = 0;
        try {
            List<Session> sessions = new ArrayList<>(scenes.values());
            for (Session session : sessions) {
                for (Entry entry : new ArrayList<>(session.entries)) {
                    tracked++;
                    try {
                        if (!Bukkit.isPrimaryThread()) {
                            inspectionFailures++;
                            continue;
                        }
                        if (!isLoaded(entry)) {
                            unloaded++;
                            continue;
                        }
                        OwnershipStatus ownership = ownershipStatus(session, entry);
                        if (ownership == OwnershipStatus.NOT_OWNED) {
                            ownershipMismatch++;
                        } else if (ownership == OwnershipStatus.INSPECTION_FAILED) {
                            inspectionFailures++;
                        } else if (restore(entry)) {
                            restored++;
                        } else {
                            restoreFailures++;
                        }
                    } catch (Throwable ignored) {
                        inspectionFailures++;
                    }
                }
            }
        } catch (Throwable ignored) {
            int uncounted = safeTrackedCount() - tracked;
            if (uncounted > 0) {
                tracked += uncounted;
                inspectionFailures += uncounted;
            }
        } finally {
            try {
                scenes.clear();
            } catch (Throwable ignored) {
                // The classloader is being discarded; reporting must remain no-throw.
            }
        }
        return new ShutdownReport(tracked, restored, unloaded, ownershipMismatch,
                restoreFailures, inspectionFailures);
    }

    private static Snapshot mutate(Session session, int requestedOperations, Mode mode) {
        Objects.requireNonNull(mode, "mode");
        int operations = Math.max(0, Math.min(session.entries.size(), requestedOperations));
        int applied = 0;
        int skipped = 0;
        session.mode = mode;
        session.revision++;

        for (int index = 0; index < operations; index++) {
            if (session.entries.isEmpty()) {
                break;
            }
            Entry entry = session.entries.get(session.mutationCursor);
            session.mutationCursor = (session.mutationCursor + 1) % session.entries.size();
            if (!isOwned(session, entry)) {
                skipped++;
                continue;
            }
            if (mutateEntry(entry, mode)) {
                applied++;
            }
        }

        session.lastMutationRequested = operations;
        session.lastMutationApplied = applied;
        session.skippedExternalCount = skipped;
        session.restoredCount = 0;
        session.restoreFailureCount = 0;
        session.inspectionFailureCount = 0;
        return snapshot(session, SceneState.READY,
                mode == Mode.ACTIVE ? "vanilla_furnace_events_primed"
                        : mode == Mode.DIRECT_WRITE ? "eventless_direct_write"
                        : "idle_normalized");
    }

    private static List<Entry> plan(Player owner, int count) {
        World world = owner.getWorld();
        int width = (int) Math.ceil(Math.sqrt(count));
        int centerX = owner.getLocation().getBlockX();
        int centerZ = owner.getLocation().getBlockZ();
        int preferredY = Math.max(world.getMinHeight(),
                Math.min(world.getMaxHeight() - 1, owner.getLocation().getBlockY() + 4));
        List<Integer> planes = searchPlanes(world, preferredY);
        List<Entry> entries = new ArrayList<>(count);

        for (int y : planes) {
            for (int zIndex = 0; zIndex < width && entries.size() < count; zIndex++) {
                int z = centerZ + zIndex - width / 2;
                for (int xIndex = 0; xIndex < width && entries.size() < count; xIndex++) {
                    int x = centerX + xIndex - width / 2;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.getType().isAir()) {
                        continue;
                    }
                    BlockState original = block.getState();
                    ItemStack[] inventory = captureInventory(original);
                    int ordinal = entries.size();
                    entries.add(new Entry(block, original, inventory,
                            MATERIAL_PATTERN[ordinal % MATERIAL_PATTERN.length], ordinal));
                }
            }
            if (entries.size() == count) {
                return entries;
            }
        }

        throw new IllegalStateException("Unable to reserve " + count
                + " loaded air blocks without overwriting world content; safeCapacity=" + entries.size());
    }

    private static List<Integer> searchPlanes(World world, int preferredY) {
        List<Integer> planes = new ArrayList<>(SEARCH_PLANES);
        for (int offset = 0; planes.size() < SEARCH_PLANES; offset++) {
            int above = preferredY + offset;
            if (above >= world.getMinHeight() && above < world.getMaxHeight()) {
                planes.add(above);
            }
            if (offset > 0 && planes.size() < SEARCH_PLANES) {
                int below = preferredY - offset;
                if (below >= world.getMinHeight() && below < world.getMaxHeight()) {
                    planes.add(below);
                }
            }
            if (above >= world.getMaxHeight() && preferredY - offset < world.getMinHeight()) {
                break;
            }
        }
        return planes;
    }

    private static void install(Session session, Entry entry, Mode initialMode) {
        Block block = entry.block;
        block.setType(entry.sceneMaterial, false);
        configureBlockData(block, entry.ordinal, false);

        BlockState state = block.getState();
        if (!(state instanceof TileState tileState)) {
            throw new IllegalStateException("Scene material did not create a TileState at " + coordinate(block));
        }
        if (state instanceof Furnace furnace) {
            configureFurnace(furnace, entry.sceneMaterial, initialMode);
        }
        tileState.getPersistentDataContainer().set(
                session.markerKey, PersistentDataType.STRING, session.markerValue);
        if (!state.update(true, false) || !isOwned(session, entry)) {
            throw new IllegalStateException("Scene ownership marker did not persist at " + coordinate(block));
        }
    }

    private static boolean mutateEntry(Entry entry, Mode mode) {
        BlockState state = entry.block.getState();
        if (state instanceof Furnace furnace) {
            configureFurnace(furnace, entry.sceneMaterial, mode);
            return state.update(true, false);
        }
        if (mode == Mode.ACTIVE) {
            return false;
        }
        return configureBlockData(entry.block, entry.ordinal, mode == Mode.DIRECT_WRITE);
    }

    private static void configureFurnace(Furnace furnace, Material material, Mode mode) {
        FurnaceInventory inventory = furnace.getSnapshotInventory();
        Material primaryInput = furnaceInput(material, false);
        ItemStack previousInput = inventory.getSmelting();
        boolean alternate = mode == Mode.DIRECT_WRITE && previousInput != null
                && previousInput.getType() == primaryInput;
        inventory.clear();
        Material input = furnaceInput(material, mode == Mode.DIRECT_WRITE && alternate);
        int amount = mode == Mode.ACTIVE ? 64 : mode == Mode.DIRECT_WRITE ? (alternate ? 33 : 17) : 32;
        inventory.setSmelting(new ItemStack(input, amount));
        if (mode == Mode.ACTIVE) {
            // Vanilla consumes this fuel on its next tick and dispatches the real
            // FurnaceStartSmeltEvent edge used by the event-driven updater.
            inventory.setFuel(new ItemStack(Material.COAL_BLOCK));
        }
        furnace.setBurnTime((short) 0);
        furnace.setCookTime((short) 0);
        // Keep all 64 inputs active through the longest formal warmup/settle/sample
        // window while still letting vanilla complete recipes and emit events.
        furnace.setCookSpeedMultiplier(mode == Mode.ACTIVE ? 0.5D : 1.0D);
    }

    private static Material furnaceInput(Material furnaceMaterial, boolean alternate) {
        if (furnaceMaterial == Material.SMOKER) {
            return alternate ? Material.CHICKEN : Material.BEEF;
        }
        return alternate ? Material.RAW_GOLD : Material.RAW_IRON;
    }

    private static boolean configureBlockData(Block block, int ordinal, boolean toggleHoney) {
        BlockData data = block.getBlockData();
        boolean changed = false;
        if (data instanceof Directional directional && directional.getFaces().contains(BlockFace.NORTH)
                && directional.getFacing() != BlockFace.NORTH) {
            directional.setFacing(BlockFace.NORTH);
            changed = true;
        }
        if (data instanceof org.bukkit.block.data.type.Beehive beehive) {
            int maximum = beehive.getMaximumHoneyLevel();
            int honeyLevel = toggleHoney
                    ? (beehive.getHoneyLevel() == maximum ? 0 : maximum)
                    : ordinal % (maximum + 1);
            if (beehive.getHoneyLevel() != honeyLevel) {
                beehive.setHoneyLevel(honeyLevel);
                changed = true;
            }
        }
        if (changed) {
            block.setBlockData(data, false);
        }
        return changed;
    }

    private static RollbackResult rollback(Session session, List<Entry> installed) {
        List<Entry> unresolved = new ArrayList<>();
        int restored = 0;
        int unloaded = 0;
        int ownershipMismatch = 0;
        int restoreFailures = 0;
        int inspectionFailures = 0;
        for (int index = installed.size() - 1; index >= 0; index--) {
            Entry entry = installed.get(index);
            if (!isLoaded(entry)) {
                unloaded++;
                unresolved.add(entry);
                continue;
            }
            OwnershipStatus ownership = ownershipStatus(session, entry);
            if (ownership == OwnershipStatus.OWNED) {
                if (restore(entry)) {
                    restored++;
                } else {
                    restoreFailures++;
                    unresolved.add(entry);
                }
            } else if (matchesOriginal(entry)) {
                // The failed install never displaced the captured air block.
            } else {
                if (ownership == OwnershipStatus.NOT_OWNED) {
                    ownershipMismatch++;
                } else {
                    inspectionFailures++;
                }
                // Marker-free or externally changed blocks are never overwritten.
                unresolved.add(entry);
            }
        }
        return new RollbackResult(unresolved, restored, unloaded, ownershipMismatch,
                restoreFailures, inspectionFailures);
    }

    private static boolean restore(Entry entry) {
        if (!isLoaded(entry)) {
            return false;
        }
        try {
            if (!entry.originalState.update(true, false)) {
                return false;
            }
            if (entry.originalInventory != null) {
                BlockState restored = entry.block.getState();
                if (!(restored instanceof Container container)) {
                    return false;
                }
                Inventory inventory = container.getSnapshotInventory();
                inventory.setContents(cloneItems(entry.originalInventory));
                if (!restored.update(true, false)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isOwned(Session session, Entry entry) {
        return ownershipStatus(session, entry) == OwnershipStatus.OWNED;
    }

    private static OwnershipStatus ownershipStatus(Session session, Entry entry) {
        if (!isLoaded(entry)) {
            return OwnershipStatus.INSPECTION_FAILED;
        }
        try {
            BlockState state = entry.block.getState();
            if (!(state instanceof TileState tileState)) {
                return OwnershipStatus.NOT_OWNED;
            }
            String marker = tileState.getPersistentDataContainer().get(
                    session.markerKey, PersistentDataType.STRING);
            return session.markerValue.equals(marker)
                    ? OwnershipStatus.OWNED : OwnershipStatus.NOT_OWNED;
        } catch (RuntimeException ignored) {
            return OwnershipStatus.INSPECTION_FAILED;
        }
    }

    private static boolean matchesOriginal(Entry entry) {
        if (!isLoaded(entry)) {
            return false;
        }
        try {
            return entry.block.getType() == entry.originalState.getType();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static ItemStack[] captureInventory(BlockState state) {
        if (!(state instanceof Container container)) {
            return null;
        }
        return cloneItems(container.getSnapshotInventory().getContents());
    }

    private static ItemStack[] cloneItems(ItemStack[] items) {
        ItemStack[] clone = new ItemStack[items.length];
        for (int index = 0; index < items.length; index++) {
            clone[index] = items[index] == null ? null : items[index].clone();
        }
        return clone;
    }

    private static boolean isLoaded(Entry entry) {
        Block block = entry.block;
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    private static List<Block> blocks(List<Entry> entries) {
        List<Block> blocks = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            blocks.add(entry.block);
        }
        return blocks;
    }

    private static Snapshot snapshot(Session session, SceneState state, String detail) {
        int owned = 0;
        int unloaded = 0;
        for (Entry entry : session.entries) {
            if (!isLoaded(entry)) {
                unloaded++;
            } else if (isOwned(session, entry)) {
                owned++;
            }
        }
        Counts counts = session.counts;
        return new Snapshot(session.ownerId, state, session.mode, session.requestedCount,
                session.placedCount, session.entries.size(), session.cleanupPending ? session.entries.size() : 0,
                owned, unloaded, counts.furnace, counts.blastFurnace, counts.smoker,
                counts.beeHive, counts.beeNest, session.revision, session.lastMutationRequested,
                session.lastMutationApplied, session.restoredCount, session.skippedExternalCount,
                session.restoreFailureCount, session.inspectionFailureCount, detail);
    }

    private static Snapshot absent(UUID ownerId, String detail) {
        return new Snapshot(ownerId, SceneState.ABSENT, null, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0L, 0, 0, 0, 0, 0, 0, detail);
    }

    private static Session requireSession(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        Session session = scenes.get(ownerId);
        if (session == null) {
            throw new IllegalStateException("No performance block scene exists for " + ownerId);
        }
        if (session.cleanupPending) {
            throw new IllegalStateException("Scene is awaiting a restoration retry");
        }
        return session;
    }

    private static int safeTrackedCount() {
        int count = 0;
        try {
            for (Session session : scenes.values()) {
                count += session.entries.size();
            }
        } catch (Throwable ignored) {
            return count;
        }
        return count;
    }

    private static NamespacedKey markerKey() {
        InteractionVisualizer plugin = InteractionVisualizer.plugin;
        if (plugin == null) {
            throw new IllegalStateException("InteractionVisualizer is not enabled");
        }
        return new NamespacedKey(plugin, OWNER_MARKER);
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Performance block scenes must run on the Bukkit primary thread");
        }
    }

    private enum OwnershipStatus {
        OWNED,
        NOT_OWNED,
        INSPECTION_FAILED
    }

    private record RollbackResult(
            List<Entry> unresolvedEntries,
            int restoredCount,
            int unloadedCount,
            int ownershipMismatchCount,
            int restoreFailureCount,
            int inspectionFailureCount
    ) {
    }

    private static String coordinate(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private static final class Session {

        private final UUID ownerId;
        private final int requestedCount;
        private final int placedCount;
        private final NamespacedKey markerKey;
        private final String markerValue;
        private final List<Entry> entries;
        private final Counts counts;

        private Mode mode;
        private long revision;
        private int mutationCursor;
        private int lastMutationRequested;
        private int lastMutationApplied;
        private int restoredCount;
        private int skippedExternalCount;
        private int restoreFailureCount;
        private int inspectionFailureCount;
        private boolean cleanupPending;

        private Session(UUID ownerId, int requestedCount, int placedCount, Mode mode, long revision,
                        NamespacedKey markerKey, String markerValue, List<Entry> entries, Counts counts) {
            this.ownerId = ownerId;
            this.requestedCount = requestedCount;
            this.placedCount = placedCount;
            this.mode = mode;
            this.revision = revision;
            this.markerKey = markerKey;
            this.markerValue = markerValue;
            this.entries = entries;
            this.counts = counts;
        }
    }

    private static final class Entry {

        private final Block block;
        private final BlockState originalState;
        private final ItemStack[] originalInventory;
        private final Material sceneMaterial;
        private final int ordinal;

        private Entry(Block block, BlockState originalState, ItemStack[] originalInventory,
                      Material sceneMaterial, int ordinal) {
            this.block = block;
            this.originalState = originalState;
            this.originalInventory = originalInventory;
            this.sceneMaterial = sceneMaterial;
            this.ordinal = ordinal;
        }
    }

    private static final class Counts {

        private int furnace;
        private int blastFurnace;
        private int smoker;
        private int beeHive;
        private int beeNest;

        private static Counts of(List<Entry> entries) {
            Counts counts = new Counts();
            for (Entry entry : entries) {
                switch (entry.sceneMaterial) {
                    case FURNACE -> counts.furnace++;
                    case BLAST_FURNACE -> counts.blastFurnace++;
                    case SMOKER -> counts.smoker++;
                    case BEEHIVE -> counts.beeHive++;
                    case BEE_NEST -> counts.beeNest++;
                    default -> throw new IllegalStateException("Unexpected scene material: " + entry.sceneMaterial);
                }
            }
            return counts;
        }
    }
}
