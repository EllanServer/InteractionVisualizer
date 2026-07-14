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
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

/**
 * Logical model for either a Paper {@link ItemDisplay} or TextDisplay.
 *
 * <p>The legacy-shaped accessors are intentionally kept internal while the
 * block renderers are migrated. No armor stand is ever spawned.</p>
 */
public class DisplayEntity extends VisualizerEntity {

    private boolean gravity;
    private boolean small;
    private boolean invulnerable;
    private EulerAngle rightArmPose;
    private EulerAngle headPose;
    private ItemStack helmet;
    private ItemStack mainHand;
    private boolean legacyRightHandItemTransform;
    private boolean legacyNameTagGeometry;
    private boolean legacyNameTagStyle;
    private Component customName;
    private String customNameRawSource;
    private boolean customNameRawSourceKnown;
    private boolean customNameVisible;
    private boolean defaultBackground;
    private Vector velocity;
    private Display.Billboard billboard;
    private float textScale;
    private boolean unboundedTextWidth;
    private float viewRange;
    private int interpolationDuration;
    private int teleportDuration;

    public DisplayEntity(Location location) {
        super(location);
        this.gravity = false;
        this.small = false;
        this.invulnerable = true;
        this.rightArmPose = EulerAngle.ZERO;
        this.headPose = EulerAngle.ZERO;
        this.helmet = ItemStack.empty();
        this.mainHand = ItemStack.empty();
        this.legacyRightHandItemTransform = false;
        this.legacyNameTagGeometry = false;
        this.legacyNameTagStyle = false;
        this.defaultBackground = false;
        this.velocity = new Vector();
        this.billboard = Display.Billboard.FIXED;
        this.textScale = 0.5F;
        this.unboundedTextWidth = false;
        this.viewRange = 1.0F;
        this.interpolationDuration = 3;
        this.teleportDuration = 3;
    }

    public Component getCustomName() {
        return customName;
    }

    public void setCustomName(String customName) {
        updateCustomName(customName);
    }

    public void setCustomName(Component customName) {
        customNameRawSource = null;
        customNameRawSourceKnown = false;
        assignCustomName(customName);
    }

    public boolean updateCustomName(String customName) {
        if (LegacyTextComponentCache.isEnabled() && customNameRawSourceKnown
                && java.util.Objects.equals(customNameRawSource, customName)) {
            if (customName != null) {
                LegacyTextComponentCache.recordSameRawFastPath();
            }
            return false;
        }
        Component parsed = customName == null ? null : LegacyTextComponentCache.parse(customName);
        boolean changed = assignCustomName(parsed);
        customNameRawSource = LegacyTextComponentCache.isEnabled() ? customName : null;
        customNameRawSourceKnown = LegacyTextComponentCache.isEnabled();
        return changed;
    }

    public boolean updateCustomName(String customName, boolean visible) {
        boolean changed = updateCustomName(customName);
        if (customNameVisible != visible) {
            setCustomNameVisible(visible);
            return true;
        }
        return changed;
    }

    private boolean assignCustomName(Component customName) {
        if (!java.util.Objects.equals(this.customName, customName)) {
            this.customName = customName;
            markDirty();
            return true;
        }
        return false;
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

    public boolean isDefaultBackground() {
        return defaultBackground;
    }

    public void setDefaultBackground(boolean defaultBackground) {
        if (this.defaultBackground != defaultBackground) {
            this.defaultBackground = defaultBackground;
            markDirty();
        }
    }

    public boolean isTextDisplay() {
        /*
         * Legacy name-tag holders remain text-shaped even while their name is
         * temporarily hidden.  Otherwise a lectern (and every other toggled
         * hologram) is first created as an empty ItemDisplay and must be
         * destroyed/re-spawned as a TextDisplay when its text becomes visible.
         */
        return legacyNameTagGeometry || customNameVisible;
    }

    public ItemStack getDisplayItem() {
        ItemStack selected = helmet.isEmpty() ? mainHand : helmet;
        return selected.clone();
    }

    public ItemDisplay.ItemDisplayTransform getItemDisplayTransform() {
        if (helmet != null && !helmet.isEmpty()) {
            return ItemDisplay.ItemDisplayTransform.HEAD;
        }
        return usesLegacyRightHandItemTransform()
                ? ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND
                : ItemDisplay.ItemDisplayTransform.FIXED;
    }

    /**
     * The public item-holding API still exposes its historical stand-shaped
     * state. An explicit profile flag distinguishes that compatibility surface
     * from ordinary fixed ItemDisplays without parsing its reserved custom name
     * on every refresh.
     */
    public boolean usesLegacyRightHandItemTransform() {
        return legacyRightHandItemTransform && (helmet == null || helmet.isEmpty());
    }

    public void setLegacyRightHandItemTransform(boolean legacyRightHandItemTransform) {
        if (!lock && this.legacyRightHandItemTransform != legacyRightHandItemTransform) {
            this.legacyRightHandItemTransform = legacyRightHandItemTransform;
            markDirty();
        }
    }

    /**
     * Marks this text as a replacement for a visible custom name on the
     * legacy marker entity. The client renders TextDisplay text from a
     * different origin, so the renderer also uses this marker to apply the
     * exact name-tag anchor compensation.
     */
    public boolean usesLegacyNameTagStyle() {
        return legacyNameTagStyle;
    }

    /**
     * Restores only the legacy name tag's 1:1 glyph size and attachment
     * origin, leaving background and billboard choices untouched.
     */
    public boolean usesLegacyNameTagGeometry() {
        return legacyNameTagGeometry;
    }

    public void useLegacyNameTagGeometry() {
        if (!legacyNameTagGeometry) {
            legacyNameTagGeometry = true;
            markDirty();
        }
        setTextScale(1.0F);
    }

    public void useLegacyNameTagStyle() {
        useLegacyNameTagGeometry();
        if (!legacyNameTagStyle) {
            legacyNameTagStyle = true;
            markDirty();
        }
        setDefaultBackground(true);
        setBillboard(Display.Billboard.CENTER);
    }

    public void setItemInMainHand(ItemStack item) {
        if (lock) {
            return;
        }
        ItemStack value = normalize(item);
        if (!mainHand.equals(value)) {
            mainHand = value;
            markDirty();
        }
    }

    public ItemStack getItemInMainHand() {
        return mainHand.clone();
    }

    public void setHelmet(ItemStack item) {
        if (lock) {
            return;
        }
        ItemStack value = normalize(item);
        if (!helmet.equals(value)) {
            helmet = value;
            markDirty();
        }
    }

    public ItemStack getHelmet() {
        return helmet.clone();
    }

    private static ItemStack normalize(ItemStack item) {
        return item == null || item.getType() == Material.AIR ? ItemStack.empty() : item.clone();
    }

    public EulerAngle getRightArmPose() {
        return rightArmPose;
    }

    public void setRightArmPose(EulerAngle pose) {
        if (!lock && !java.util.Objects.equals(rightArmPose, pose)) {
            rightArmPose = pose == null ? EulerAngle.ZERO : pose;
            markDirty();
        }
    }

    public EulerAngle getHeadPose() {
        return headPose;
    }

    public void setHeadPose(EulerAngle pose) {
        if (!lock && !java.util.Objects.equals(headPose, pose)) {
            headPose = pose == null ? EulerAngle.ZERO : pose;
            markDirty();
        }
    }

    public Vector getVelocity() {
        return velocity.clone();
    }

    public void setVelocity(Vector velocity) {
        Vector value = velocity == null ? new Vector() : velocity.clone();
        if (!this.velocity.equals(value)) {
            this.velocity = value;
            markDirty();
        }
    }

    public boolean hasGravity() {
        return gravity;
    }

    public void setGravity(boolean gravity) {
        if (this.gravity != gravity) {
            this.gravity = gravity;
            markDirty();
        }
    }

    public boolean isSmall() {
        return small;
    }

    public void setSmall(boolean small) {
        if (this.small != small) {
            this.small = small;
            markDirty();
        }
    }

    public boolean isInvulnerable() {
        return invulnerable;
    }

    public void setInvulnerable(boolean invulnerable) {
        if (this.invulnerable != invulnerable) {
            this.invulnerable = invulnerable;
            markDirty();
        }
    }

    public Display.Billboard getBillboard() {
        return billboard;
    }

    public void setBillboard(Display.Billboard billboard) {
        Display.Billboard value = billboard == null ? Display.Billboard.FIXED : billboard;
        if (this.billboard != value) {
            this.billboard = value;
            markDirty();
        }
    }

    public float getTextScale() {
        return textScale;
    }

    public void setTextScale(float textScale) {
        float value = Float.isFinite(textScale) ? Math.max(0.0F, textScale) : 0.5F;
        if (this.textScale != value) {
            this.textScale = value;
            markDirty();
        }
    }

    public boolean usesUnboundedTextWidth() {
        return unboundedTextWidth || legacyNameTagStyle;
    }

    public void setUnboundedTextWidth(boolean unboundedTextWidth) {
        if (this.unboundedTextWidth != unboundedTextWidth) {
            this.unboundedTextWidth = unboundedTextWidth;
            markDirty();
        }
    }

    public float getViewRange() {
        return viewRange;
    }

    public void setViewRange(float viewRange) {
        float value = Float.isFinite(viewRange) ? Math.max(0.0F, viewRange) : 1.0F;
        if (this.viewRange != value) {
            this.viewRange = value;
            markDirty();
        }
    }

    public int getInterpolationDuration() {
        return interpolationDuration;
    }

    public void setInterpolationDuration(int duration) {
        int value = Math.max(0, duration);
        if (interpolationDuration != value) {
            interpolationDuration = value;
            markDirty();
        }
    }

    public int getTeleportDuration() {
        return teleportDuration;
    }

    public void setTeleportDuration(int duration) {
        int value = Math.max(0, Math.min(59, duration));
        if (teleportDuration != value) {
            teleportDuration = value;
            markDirty();
        }
    }

    /* Legacy visual flags no longer have a Display-entity equivalent. */
    public void setArms(boolean ignored) { }
    public boolean hasArms() { return false; }
    public void setBasePlate(boolean ignored) { }
    public boolean hasBasePlate() { return false; }
    public void setMarker(boolean ignored) { }
    public boolean isMarker() { return true; }
    public void setVisible(boolean ignored) { }
    public boolean isVisible() { return true; }

    @Override
    public double getHeight() {
        return isTextDisplay() ? 0.3 : (small ? 0.5 : 1.0);
    }
}
