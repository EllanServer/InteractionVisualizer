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

    /**
     * Rendering presentation for one logical item.
     *
     * <p>{@link #DROPPED} preserves the Furnace/Sparrow fake-item path. The
     * remaining modes use a fixed Paper ItemDisplay while sharing the same
     * logical lifecycle, viewer filtering, updates, and removal API.</p>
     */
    public enum RenderMode {
        DROPPED,
        ITEM,
        BLOCK,
        LOW_BLOCK,
        TOOL,
        STANDING,
        BANNER,
        FRAME;

        public boolean isFixedDisplay() {
            return this != DROPPED;
        }
    }

    private ItemStack item;
    private RenderMode renderMode;
    private int frameRotation;
    private boolean gravity;
    private boolean glowing;
    private int pickupDelay;
    private Component customName;
    private String customNameRawSource;
    private boolean customNameRawSourceKnown;
    private boolean customNameVisible;
    private Vector velocity;

    public Item(Location location) {
        this(location, RenderMode.DROPPED);
    }

    public Item(Location location, RenderMode renderMode) {
        super(location);
        this.renderMode = java.util.Objects.requireNonNull(renderMode, "renderMode");
        this.item = renderMode.isFixedDisplay() ? ItemStack.empty() : ItemStack.of(Material.STONE);
        this.gravity = false;
        this.velocity = new Vector();
    }

    public RenderMode getRenderMode() {
        return renderMode;
    }

    public void setRenderMode(RenderMode renderMode) {
        if (lock) {
            return;
        }
        RenderMode value = java.util.Objects.requireNonNull(renderMode, "renderMode");
        if (this.renderMode != value) {
            this.renderMode = value;
            if (!value.isFixedDisplay() && item.isEmpty()) {
                item = ItemStack.of(Material.STONE);
            }
            markDirty();
        }
    }

    public boolean isFixedDisplay() {
        return renderMode.isFixedDisplay();
    }

    public int getFrameRotation() {
        return frameRotation;
    }

    public void setFrameRotation(int frameRotation) {
        if (frameRotation < 0 || frameRotation > 7) {
            throw new IllegalArgumentException("Item frame rotation must be between 0 and 7");
        }
        if (!lock && this.frameRotation != frameRotation) {
            this.frameRotation = frameRotation;
            markDirty();
        }
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
        ItemStack normalized = value == null || value.isEmpty()
                ? (renderMode.isFixedDisplay() ? ItemStack.empty() : ItemStack.of(Material.STONE))
                : value.clone();
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
        return renderMode == RenderMode.BANNER ? 1.5
                : (renderMode == RenderMode.FRAME ? 0.75 : (renderMode.isFixedDisplay() ? 0.5 : 0.25));
    }
}
