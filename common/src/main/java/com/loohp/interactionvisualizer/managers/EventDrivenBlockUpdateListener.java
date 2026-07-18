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

import com.loohp.interactionvisualizer.api.events.TileEntityActivatedEvent;
import com.loohp.interactionvisualizer.api.events.TileEntityAddedEvent;
import com.loohp.interactionvisualizer.api.events.TileEntityDeactivatedEvent;
import com.loohp.interactionvisualizer.api.events.TileEntityRemovedEvent;
import com.loohp.interactionvisualizer.blocks.BeeHiveDisplay;
import com.loohp.interactionvisualizer.blocks.BeeNestDisplay;
import com.loohp.interactionvisualizer.blocks.BlastFurnaceDisplay;
import com.loohp.interactionvisualizer.blocks.BlockUpdateCoordinator;
import com.loohp.interactionvisualizer.blocks.FurnaceDisplay;
import com.loohp.interactionvisualizer.blocks.SmokerDisplay;
import com.loohp.interactionvisualizer.objectholders.TileEntity.TileEntityType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Bee;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityEnterBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Listener surface used exclusively by the startup-locked event-driven block
 * update mode. Keeping these handlers off the display listeners means the
 * legacy mode pays no Bukkit dispatch cost for them.
 */
public final class EventDrivenBlockUpdateListener implements Listener {

    private static final BlockFace[] REDSTONE_NEIGHBORS = {
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST,
            BlockFace.UP, BlockFace.DOWN
    };

    private FurnaceDisplay furnace;
    private BlastFurnaceDisplay blastFurnace;
    private SmokerDisplay smoker;
    private BeeHiveDisplay beeHive;
    private BeeNestDisplay beeNest;

    public void add(FurnaceDisplay display) {
        this.furnace = display;
    }

    public void add(BlastFurnaceDisplay display) {
        this.blastFurnace = display;
    }

    public void add(SmokerDisplay display) {
        this.smoker = display;
    }

    public void add(BeeHiveDisplay display) {
        this.beeHive = display;
    }

    public void add(BeeNestDisplay display) {
        this.beeNest = display;
    }

    public boolean isEmpty() {
        return BlockUpdateCoordinator.isEmpty();
    }

    /*
     * FurnaceBurnEvent is intentionally not part of this listener. Paper
     * 26.1.2 emits it as a per-tick level signal for processing furnaces, so
     * treating it as a dirty edge permanently saturates all three furnace
     * schedulers. StartSmelt/Smelt/inventory/lifecycle edges bootstrap work;
     * the active cadence owns progress and fuel-state refreshes thereafter.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceStartSmelt(FurnaceStartSmeltEvent event) {
        BlockUpdateCoordinator.markDirtyUnlessActive(event.getBlock());
        switch (furnaceTarget(event.getBlock().getType())) {
            case FURNACE -> {
                if (furnace != null) {
                    furnace.onFurnaceStartSmelt(event);
                }
            }
            case BLAST_FURNACE -> {
                if (blastFurnace != null) {
                    blastFurnace.onBlastFurnaceStartSmelt(event);
                }
            }
            case SMOKER -> {
                if (smoker != null) {
                    smoker.onSmokerStartSmelt(event);
                }
            }
            case NONE -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        BlockUpdateCoordinator.markDirtyUnlessActive(event.getBlock());
        switch (furnaceTarget(event.getBlock().getType())) {
            case FURNACE -> {
                if (furnace != null) {
                    furnace.onFurnaceSmelt(event);
                }
            }
            case BLAST_FURNACE -> {
                if (blastFurnace != null) {
                    blastFurnace.onBlastFurnaceSmelt(event);
                }
            }
            case SMOKER -> {
                if (smoker != null) {
                    smoker.onSmokerSmelt(event);
                }
            }
            case NONE -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        BlockUpdateCoordinator.markDirty(event.getBlock());
        switch (furnaceTarget(event.getBlock().getType())) {
            case FURNACE -> {
                if (furnace != null) {
                    furnace.onFurnaceExtract(event);
                }
            }
            case BLAST_FURNACE -> {
                if (blastFurnace != null) {
                    blastFurnace.onBlastFurnaceExtract(event);
                }
            }
            case SMOKER -> {
                if (smoker != null) {
                    smoker.onSmokerExtract(event);
                }
            }
            case NONE -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        Inventory sourceInventory = event.getSource();
        Block source = inventoryBlock(sourceInventory);
        routeInventoryBlock(source);

        Inventory destinationInventory = event.getDestination();
        Block destination = destinationInventory == sourceInventory ? source : inventoryBlock(destinationInventory);
        if (destination != null && !destination.equals(source)) {
            routeInventoryBlock(destination);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTileEntityAdded(TileEntityAddedEvent event) {
        BlockUpdateCoordinator.markDirty(event.getBlock());
        TileEntityType type = event.getTileEntityType();
        if (type == null) {
            return;
        }
        switch (type) {
            case FURNACE -> {
                if (furnace != null) {
                    furnace.onFurnaceAdded(event);
                }
            }
            case BLAST_FURNACE -> {
                if (blastFurnace != null) {
                    blastFurnace.onBlastFurnaceAdded(event);
                }
            }
            case SMOKER -> {
                if (smoker != null) {
                    smoker.onSmokerAdded(event);
                }
            }
            case BEEHIVE -> {
                if (beeHive != null) {
                    beeHive.onBeehiveAdded(event);
                }
            }
            case BEE_NEST -> {
                if (beeNest != null) {
                    beeNest.onBeenestAdded(event);
                }
            }
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTileEntityActivated(TileEntityActivatedEvent event) {
        BlockUpdateCoordinator.markDirty(event.getBlock());
        TileEntityType type = event.getTileEntityType();
        if (type == null) {
            return;
        }
        switch (type) {
            case FURNACE -> {
                if (furnace != null) {
                    furnace.onFurnaceActivated(event);
                }
            }
            case BLAST_FURNACE -> {
                if (blastFurnace != null) {
                    blastFurnace.onBlastFurnaceActivated(event);
                }
            }
            case SMOKER -> {
                if (smoker != null) {
                    smoker.onSmokerActivated(event);
                }
            }
            case BEEHIVE -> {
                if (beeHive != null) {
                    beeHive.onBeehiveActivated(event);
                }
            }
            case BEE_NEST -> {
                if (beeNest != null) {
                    beeNest.onBeenestActivated(event);
                }
            }
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTileEntityDeactivated(TileEntityDeactivatedEvent event) {
        BlockUpdateCoordinator.remove(event.getBlock());
        TileEntityType type = event.getTileEntityType();
        if (type == null) {
            return;
        }
        switch (type) {
            case FURNACE -> {
                if (furnace != null) {
                    furnace.onFurnaceDeactivated(event);
                }
            }
            case BLAST_FURNACE -> {
                if (blastFurnace != null) {
                    blastFurnace.onBlastFurnaceDeactivated(event);
                }
            }
            case SMOKER -> {
                if (smoker != null) {
                    smoker.onSmokerDeactivated(event);
                }
            }
            case BEEHIVE -> {
                if (beeHive != null) {
                    beeHive.onBeehiveDeactivated(event);
                }
            }
            case BEE_NEST -> {
                if (beeNest != null) {
                    beeNest.onBeenestDeactivated(event);
                }
            }
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTileEntityRemoved(TileEntityRemovedEvent event) {
        BlockUpdateCoordinator.remove(event.getBlock());
        if (event.getTileEntityType() == TileEntityType.SMOKER && smoker != null) {
            smoker.onRemoveSmoker(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAffectedBlockPlace(BlockPlaceEvent event) {
        routeCoordinatorChange(event.getBlockPlaced());
        routeAffectedColumn(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAffectedBlockBreak(BlockBreakEvent event) {
        BlockUpdateCoordinator.remove(event.getBlock());
        routeCoordinatorStructureChange(event.getBlock());
        routeAffectedColumn(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAffectedBlockBurn(BlockBurnEvent event) {
        BlockUpdateCoordinator.remove(event.getBlock());
        routeCoordinatorStructureChange(event.getBlock());
        routeAffectedColumn(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAffectedBlockFade(BlockFadeEvent event) {
        routeCoordinatorChange(event.getBlock());
        routeAffectedColumn(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAffectedBlockIgnite(BlockIgniteEvent event) {
        routeCoordinatorChange(event.getBlock());
        routeAffectedColumn(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAffectedFluidFlow(BlockFromToEvent event) {
        routeCoordinatorChange(event.getToBlock());
        routeAffectedColumn(event.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAffectedBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            BlockUpdateCoordinator.remove(block);
            routeCoordinatorStructureChange(block);
            routeAffectedColumn(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAffectedEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            BlockUpdateCoordinator.remove(block);
            routeCoordinatorStructureChange(block);
            routeAffectedColumn(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispenserHarvest(BlockDispenseEvent event) {
        BlockUpdateCoordinator.markDirty(event.getBlock());
        Material dispensed = event.getItem().getType();
        if (dispensed != Material.GLASS_BOTTLE && dispensed != Material.SHEARS) {
            return;
        }
        BlockData data = event.getBlock().getBlockData();
        if (data instanceof Directional directional) {
            routeBeeBlock(event.getBlock().getRelative(directional.getFacing()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBeeEnterBlock(EntityEnterBlockEvent event) {
        if (hasBeeDisplays() && event.getEntity() instanceof Bee) {
            routeBeeBlock(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        routeCoordinatorChange(block);
        if (event.getEntity() instanceof Bee && beeTarget(block.getType()) != BeeTarget.NONE) {
            routeBeeBlock(block);
            return;
        }
        // Falling blocks, Endermen and other entity-driven changes can alter
        // the unobstructed campfire smoke column even when no bee is involved.
        routeAffectedColumn(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBeeRelevantInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        BlockUpdateCoordinator.markDirty(block);
        Material material = block.getType();
        if (beeTarget(material) != BeeTarget.NONE) {
            routeBeeBlock(block);
        } else if (isSmokeColumnInteractable(material)) {
            routeAffectedColumn(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAffectedPistonExtend(BlockPistonExtendEvent event) {
        Set<Block> affected = pistonAffectedBlocks(event.getBlock(), event.getBlocks(),
                event.getDirection(), event.getDirection());
        routeCoordinatorChanges(affected);
        routeAffectedColumns(affected);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAffectedPistonRetract(BlockPistonRetractEvent event) {
        Set<Block> affected = pistonAffectedBlocks(event.getBlock(), event.getBlocks(),
                event.getDirection(), event.getDirection().getOppositeFace());
        routeCoordinatorChanges(affected);
        routeAffectedColumns(affected);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAffectedRedstone(BlockRedstoneEvent event) {
        if (event.getOldCurrent() == event.getNewCurrent()) {
            return;
        }
        Set<Block> changedOpenables = null;
        Block source = event.getBlock();
        BlockUpdateCoordinator.markDirty(source);
        if (isRedstoneOpenable(source.getType())) {
            changedOpenables = new LinkedHashSet<>();
            changedOpenables.add(source);
        }
        for (BlockFace face : REDSTONE_NEIGHBORS) {
            Block neighbor = source.getRelative(face);
            BlockUpdateCoordinator.markDirty(neighbor);
            if (isRedstoneOpenable(neighbor.getType())) {
                if (changedOpenables == null) {
                    changedOpenables = new LinkedHashSet<>();
                }
                changedOpenables.add(neighbor);
            }
        }
        if (changedOpenables != null) {
            routeAffectedColumns(changedOpenables);
        }
    }

    private void routeInventoryBlock(Block block) {
        if (block == null) {
            return;
        }
        BlockUpdateCoordinator.markDirty(block);
        switch (furnaceTarget(block.getType())) {
            case FURNACE -> {
                if (furnace != null) {
                    furnace.onFurnaceInventoryChanged(block);
                }
            }
            case BLAST_FURNACE -> {
                if (blastFurnace != null) {
                    blastFurnace.onBlastFurnaceInventoryChanged(block);
                }
            }
            case SMOKER -> {
                if (smoker != null) {
                    smoker.onSmokerInventoryChanged(block);
                }
            }
            case NONE -> {
            }
        }
    }

    private boolean hasBeeDisplays() {
        return beeHive != null || beeNest != null;
    }

    private void routeCoordinatorChanges(Collection<Block> changedBlocks) {
        if (changedBlocks == null) {
            return;
        }
        for (Block block : changedBlocks) {
            routeCoordinatorChange(block);
        }
    }

    private void routeCoordinatorChange(Block changedBlock) {
        BlockUpdateCoordinator.markDirty(changedBlock);
        routeCoordinatorStructureChange(changedBlock);
    }

    private void routeCoordinatorStructureChange(Block changedBlock) {
        if (changedBlock == null) {
            return;
        }
        if (BlockUpdateCoordinator.tracks(Material.BEACON)) {
            for (int vertical = 1; vertical <= 4; vertical++) {
                for (int x = -vertical; x <= vertical; x++) {
                    for (int z = -vertical; z <= vertical; z++) {
                        Block candidate = changedBlock.getRelative(x, vertical, z);
                        if (candidate.getType() == Material.BEACON) {
                            BlockUpdateCoordinator.markDirty(candidate);
                        }
                    }
                }
            }
        }
        if (BlockUpdateCoordinator.tracks(Material.CONDUIT)) {
            for (int x = -2; x <= 2; x++) {
                for (int y = -2; y <= 2; y++) {
                    for (int z = -2; z <= 2; z++) {
                        Block candidate = changedBlock.getRelative(x, y, z);
                        if (candidate.getType() == Material.CONDUIT) {
                            BlockUpdateCoordinator.markDirty(candidate);
                        }
                    }
                }
            }
        }
    }

    private void routeAffectedColumn(Block changedBlock) {
        if (changedBlock == null || beeHive == null && beeNest == null) {
            return;
        }
        scanAffectedColumn(changedBlock, this::routeBeeBlock);
    }

    private void routeAffectedColumns(Collection<Block> changedBlocks) {
        if (changedBlocks == null || changedBlocks.isEmpty() || beeHive == null && beeNest == null) {
            return;
        }
        scanAffectedColumns(changedBlocks, this::routeBeeBlock);
    }

    private void routeBeeBlock(Block block) {
        if (block == null) {
            return;
        }
        switch (beeTarget(block.getType())) {
            case HIVE -> {
                if (beeHive != null) {
                    beeHive.onAffectedBeeBlock(block);
                }
            }
            case NEST -> {
                if (beeNest != null) {
                    beeNest.onAffectedBeeBlock(block);
                }
            }
            case NONE -> {
            }
        }
    }

    static Block inventoryBlock(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        try {
            Location location = inventory.getLocation();
            return location == null ? null : location.getBlock();
        } catch (Exception | AbstractMethodError ignored) {
            return null;
        }
    }

    static FurnaceTarget furnaceTarget(Material material) {
        if (material == null) {
            return FurnaceTarget.NONE;
        }
        return switch (material) {
            case FURNACE -> FurnaceTarget.FURNACE;
            case BLAST_FURNACE -> FurnaceTarget.BLAST_FURNACE;
            case SMOKER -> FurnaceTarget.SMOKER;
            default -> FurnaceTarget.NONE;
        };
    }

    static BeeTarget beeTarget(Material material) {
        if (material == null) {
            return BeeTarget.NONE;
        }
        return switch (material) {
            case BEEHIVE -> BeeTarget.HIVE;
            case BEE_NEST -> BeeTarget.NEST;
            default -> BeeTarget.NONE;
        };
    }

    static boolean isRedstoneOpenable(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.endsWith("_TRAPDOOR") || name.endsWith("_DOOR") || name.endsWith("_FENCE_GATE");
    }

    static boolean isSmokeColumnInteractable(Material material) {
        return material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE
                || isRedstoneOpenable(material);
    }

    static Set<Block> pistonAffectedBlocks(Block piston, Collection<Block> movedBlocks,
                                           BlockFace movementDirection, BlockFace headDirection) {
        Set<Block> affected = new LinkedHashSet<>();
        if (piston != null) {
            affected.add(piston);
            affected.add(piston.getRelative(headDirection));
        }
        if (movedBlocks != null) {
            for (Block moved : movedBlocks) {
                if (moved != null) {
                    affected.add(moved);
                    affected.add(moved.getRelative(movementDirection));
                }
            }
        }
        return affected;
    }

    static void scanAffectedColumn(Block changedBlock, Consumer<Block> route) {
        if (changedBlock == null || route == null) {
            return;
        }
        for (int distance = 1; distance <= 5; distance++) {
            Block candidate = changedBlock.getRelative(BlockFace.UP, distance);
            if (beeTarget(candidate.getType()) != BeeTarget.NONE) {
                route.accept(candidate);
            }
        }
    }

    static void scanAffectedColumns(Collection<Block> changedBlocks, Consumer<Block> route) {
        if (changedBlocks == null || changedBlocks.isEmpty() || route == null) {
            return;
        }
        Set<Block> routed = new LinkedHashSet<>();
        for (Block changedBlock : changedBlocks) {
            scanAffectedColumn(changedBlock, candidate -> {
                if (routed.add(candidate)) {
                    route.accept(candidate);
                }
            });
        }
    }

    enum FurnaceTarget {
        FURNACE,
        BLAST_FURNACE,
        SMOKER,
        NONE
    }

    enum BeeTarget {
        HIVE,
        NEST,
        NONE
    }
}
