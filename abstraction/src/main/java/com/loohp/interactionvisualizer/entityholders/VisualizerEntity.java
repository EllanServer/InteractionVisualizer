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
 */

package com.loohp.interactionvisualizer.entityholders;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable logical state for one visual entity.
 *
 * <p>The old implementation allocated a synthetic packet entity id and rebuilt a
 * deep hash of every field on every polling pass. Paper now owns the real
 * entity; this holder only tracks logical state and an O(1) revision number.</p>
 */
public abstract class VisualizerEntity implements IVisualizerEntity {

    protected final UUID uuid;
    protected Location location;
    protected boolean lock;
    protected boolean isSilent;

    private final AtomicInteger revision;
    private volatile Entity bukkitEntity;

    protected VisualizerEntity(Location location) {
        if (location == null || location.getWorld() == null) {
            throw new IllegalArgumentException("A visual entity requires a world-backed location");
        }
        this.uuid = UUID.randomUUID();
        this.location = location.clone();
        this.lock = false;
        this.isSilent = true;
        this.revision = new AtomicInteger(1);
    }

    public final int cacheCode() {
        return revision.get();
    }

    protected final void markDirty() {
        revision.updateAndGet(value -> value == Integer.MAX_VALUE ? 1 : value + 1);
    }

    public final Optional<Entity> getBukkitEntity() {
        Entity entity = bukkitEntity;
        return entity != null && entity.isValid() ? Optional.of(entity) : Optional.empty();
    }

    public final void bind(Entity entity) {
        this.bukkitEntity = entity;
    }

    public final void unbind() {
        this.bukkitEntity = null;
    }

    @Override
    public final int getEntityId() {
        Entity entity = bukkitEntity;
        return entity == null || !entity.isValid() ? -1 : entity.getEntityId();
    }

    @Override
    public void setRotation(float yaw, float pitch) {
        if (lock || (location.getYaw() == yaw && location.getPitch() == pitch)) {
            return;
        }
        location.setYaw(yaw);
        location.setPitch(pitch);
        markDirty();
    }

    @Override
    public World getWorld() {
        return location.getWorld();
    }

    @Override
    public void teleport(Location location) {
        if (lock) {
            return;
        }
        this.location = location.clone();
        markDirty();
    }

    @Override
    public void teleport(World world, double x, double y, double z) {
        teleport(new Location(world, x, y, z, location.getYaw(), location.getPitch()));
    }

    @Override
    public void teleport(World world, double x, double y, double z, float yaw, float pitch) {
        teleport(new Location(world, x, y, z, yaw, pitch));
    }

    @Override
    public Location getLocation() {
        return location.clone();
    }

    @Override
    public void setLocation(Location location) {
        teleport(location);
    }

    @Override
    public boolean isSilent() {
        return isSilent;
    }

    @Override
    public void setSilent(boolean silent) {
        if (isSilent != silent) {
            isSilent = silent;
            markDirty();
        }
    }

    @Override
    public UUID getUniqueId() {
        return uuid;
    }

    @Override
    public boolean isLocked() {
        return lock;
    }

    @Override
    public void setLocked(boolean locked) {
        this.lock = locked;
    }
}
