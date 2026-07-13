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

import com.loohp.interactionvisualizer.utils.LegacyTextComponentCache;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Logical state for a client-side vanilla item visual.
 * Velocity and gravity are animation inputs; no server item physics is enabled.
 */
public class Item extends VisualizerEntity {

    private ItemStack item;
    private boolean gravity;
    private boolean glowing;
    private int pickupDelay;
    private Component customName;
    private String customNameRawSource;
    private boolean customNameRawSourceKnown;
    private boolean customNameVisible;
    private Vector velocity;

    public Item(Location location) {
        super(location);
        this.item = ItemStack.of(Material.STONE);
        this.gravity = false;
        this.velocity = new Vector();
    }

    public Component getCustomName() {
        return customName;
    }

    public void setCustomName(String name) {
        updateCustomName(name);
    }

    public void setCustomName(Component name) {
        customNameRawSource = null;
        customNameRawSourceKnown = false;
        assignCustomName(name);
    }

    public boolean updateCustomName(String name) {
        if (LegacyTextComponentCache.isEnabled() && customNameRawSourceKnown
                && java.util.Objects.equals(customNameRawSource, name)) {
            if (name != null) {
                LegacyTextComponentCache.recordSameRawFastPath();
            }
            return false;
        }
        Component parsed = name == null ? null : LegacyTextComponentCache.parse(name);
        boolean changed = assignCustomName(parsed);
        customNameRawSource = LegacyTextComponentCache.isEnabled() ? name : null;
        customNameRawSourceKnown = LegacyTextComponentCache.isEnabled();
        return changed;
    }

    private boolean assignCustomName(Component name) {
        if (!java.util.Objects.equals(customName, name)) {
            customName = name;
            markDirty();
            return true;
        }
        return false;
    }

    public boolean isGlowing() {
        return glowing;
    }

    public void setGlowing(boolean glowing) {
        if (this.glowing != glowing) {
            this.glowing = glowing;
            markDirty();
        }
    }

    public boolean isCustomNameVisible() {
        return customNameVisible;
    }

    public void setCustomNameVisible(boolean visible) {
        if (customNameVisible != visible) {
            customNameVisible = visible;
            markDirty();
        }
    }

    public void setItemStack(ItemStack item, boolean force) {
        if (!lock || force) {
            setItemInternal(item);
        }
    }

    public void setItemStack(ItemStack item) {
        if (!lock) {
            setItemInternal(item);
        }
    }

    private void setItemInternal(ItemStack value) {
        ItemStack normalized = value == null || value.isEmpty() ? ItemStack.of(Material.STONE) : value.clone();
        if (!item.equals(normalized)) {
            item = normalized;
            markDirty();
        }
    }

    public ItemStack getItemStack() {
        return item.clone();
    }

    public void setGravity(boolean gravity) {
        if (this.gravity != gravity) {
            this.gravity = gravity;
            markDirty();
        }
    }

    public boolean hasGravity() {
        return gravity;
    }

    public Vector getVelocity() {
        return velocity.clone();
    }

    public void setVelocity(Vector velocity) {
        Vector normalized = velocity == null ? new Vector() : velocity.clone();
        normalized.checkFinite();
        if (!this.velocity.equals(normalized)) {
            this.velocity = normalized;
            markDirty();
        }
    }

    public int getPickupDelay() {
        return pickupDelay;
    }

    public void setPickupDelay(int pickupDelay) {
        if (this.pickupDelay != pickupDelay) {
            this.pickupDelay = pickupDelay;
            markDirty();
        }
    }

    @Override
    public double getHeight() {
        return 0.25;
    }
}
