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

package com.loohp.interactionvisualizer.utils;

import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.entityholders.ItemFrame;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.EulerAngle;

/** Centralized transforms replacing armor-stand arm/head pose encoding. */
public final class DisplayTransformFactory {

    private static final float WORKSTATION_BASE_Y = (float) (0.600781 - WorkstationDisplayPositioning.SURFACE_ANCHOR_Y);
    private static final float LEGACY_COMMON_ROTATION = (float) Math.toRadians(100.0);
    private static final float LEGACY_LATERAL_ROTATION = (float) Math.toRadians(90.0);
    private static final float DIAGONAL_YAW = (float) Math.toRadians(45.0);
    private static final double LEGACY_NAME_TAG_WORLD_Y = 0.5D;
    private static final float TEXT_PIXEL_SCALE = 0.025F;
    private static final float SINGLE_LINE_BASELINE_PIXELS = 9.0F;

    private DisplayTransformFactory() {
    }

    public static Matrix4f item(DisplayEntity state) {
        if (state.usesLegacyRightHandItemTransform()) {
            String displayMode = legacyMode(state);
            LegacyHandProfile profile = legacyHandProfile(displayMode);
            float modeYaw = profile.diagonal() ? DIAGONAL_YAW : 0.0F;
            float displayPitch = (float) Math.toRadians(state.getLocation().getPitch());
            return legacyRightHand(profile, state.getRightArmPose(), state.isSmall(), 0.0F,
                    modeYaw, modeYaw, displayPitch);
        }
        return item(mode(state), state.isSmall());
    }

    public static Matrix4f item(Item state) {
        if (WorkstationDisplayPositioning.isWorkstationItem(state)) {
            LegacyHandProfile profile = legacyHandProfile(state.getRenderMode());
            float displayYaw = state.getLocation().getYaw();
            float commonYaw = (float) Math.toRadians(displayYaw - WorkstationDisplayPositioning.gridYaw(state));
            float modeYaw = profile.diagonal() ? DIAGONAL_YAW : 0.0F;
            return legacyRightHand(profile, profile.pose(), true, WORKSTATION_BASE_Y,
                    commonYaw, modeYaw, 0.0F);
        }
        return item(state.getRenderMode(), state.getFrameRotation());
    }

    static Matrix4f item(Item.RenderMode mode) {
        return item(mode, 0);
    }

    private static Matrix4f item(Item.RenderMode mode, int frameRotation) {
        if (mode == Item.RenderMode.BANNER) {
            return legacySmallHeadBanner();
        }
        if (mode == Item.RenderMode.FRAME) {
            return new Matrix4f().identity()
                    .rotateZ((float) Math.toRadians(frameRotation * 45.0))
                    .scale(0.5F);
        }
        String transformMode = switch (mode) {
            case BLOCK -> "block";
            case LOW_BLOCK -> "lowblock";
            case TOOL -> "tool";
            case STANDING -> "standing";
            case BANNER, FRAME -> throw new IllegalStateException("handled above");
            case ITEM, DROPPED -> "item";
        };
        return item(transformMode, true);
    }

    static Matrix4f item(String mode, boolean small) {
        Matrix4f matrix = new Matrix4f().identity();

        switch (mode) {
            case "block" -> matrix.translate(0.0F, 0.08F, 0.0F).rotateX((float) Math.toRadians(90)).scale(0.42F);
            case "lowblock" -> matrix.translate(0.0F, 0.02F, 0.0F).rotateX((float) Math.toRadians(90)).scale(0.36F);
            case "tool" -> matrix.rotateZ((float) Math.toRadians(-90)).scale(0.48F);
            case "standing" -> matrix.translate(0.0F, 0.18F, 0.0F).scale(0.5F);
            default -> matrix.scale(small ? 0.42F : 0.5F);
        }
        return matrix;
    }

    public static ItemDisplay.ItemDisplayTransform itemDisplayTransform(Item state) {
        if (WorkstationDisplayPositioning.isWorkstationItem(state)) {
            return ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND;
        }
        return itemDisplayTransform(state.getRenderMode());
    }

    public static ItemDisplay.ItemDisplayTransform itemDisplayTransform(DisplayEntity state) {
        return state.getItemDisplayTransform();
    }

    static ItemDisplay.ItemDisplayTransform itemDisplayTransform(Item.RenderMode mode) {
        return mode == Item.RenderMode.BANNER
                ? ItemDisplay.ItemDisplayTransform.HEAD
                : ItemDisplay.ItemDisplayTransform.FIXED;
    }

    private static Matrix4f legacySmallHeadBanner() {
        return new Matrix4f().identity()
                // The physical ItemDisplay remains one block above the old
                // marker so it samples the same light without a fake light.
                .translate(0.0F, -0.97F, 0.0F)
                .rotateY((float) Math.PI)
                .scale(-1.0F, -1.0F, 1.0F)
                .translate(0.0F, -1.501F, 0.0F)
                // Small legacy-model head pivot and scale.
                .translate(0.0F, 12.75F / 16.0F, 0.0F)
                .scale(0.75F)
                // Vanilla custom-head layer item transform.
                .translate(0.0F, -0.25F, 0.0F)
                .rotateY((float) Math.PI)
                .scale(0.625F, -0.625F, -0.625F)
                // ItemDisplayRenderer adds Y-180 before the HEAD context.
                .rotateY((float) Math.PI);
    }

    private static Matrix4f legacyRightHand(LegacyHandProfile profile, EulerAngle pose, boolean small,
                                            float commonY, float commonYaw, float modeYaw,
                                            float displayPitch) {
        // Bukkit's Location#getDirection includes pitch. The legacy API used
        // that full direction for both its common hand anchor and every mode
        // teleport, while the legacy stand renderer did not pitch the model.
        Vector3f direction = new Vector3f(0.0F, -(float) Math.sin(displayPitch),
                (float) Math.cos(displayPitch));
        Vector3f commonOffset = rotateY(new Vector3f(direction).mul(0.19F), LEGACY_COMMON_ROTATION)
                .add(new Vector3f(direction).mul(-0.11F))
                .add(0.0F, commonY, 0.0F);
        rotateY(commonOffset, commonYaw);
        Vector3f modeOffset = new Vector3f(direction).mul(profile.forward())
                .add(rotateY(new Vector3f(direction).mul(profile.lateral()), LEGACY_LATERAL_ROTATION))
                .add(0.0F, profile.vertical(), 0.0F);
        rotateY(modeOffset, modeYaw);
        Vector3f localOffset = commonOffset.add(modeOffset);

        Matrix4f matrix = new Matrix4f().identity()
                // DisplayRenderer FIXED applies +entityPitch before the custom
                // transformation. The legacy renderer did not, so cancel it.
                .rotateX(-displayPitch)
                .translate(localOffset)
                .rotateY((float) Math.PI)
                .scale(-1.0F, -1.0F, 1.0F)
                .translate(0.0F, -1.501F, 0.0F);

        if (small) {
            matrix.translate(-2.5F / 16.0F, 13.0F / 16.0F, 0.0F);
        } else {
            matrix.translate(-5.0F / 16.0F, 2.0F / 16.0F, 0.0F);
        }

        matrix.rotateZYX((float) pose.getZ(), (float) pose.getY(), (float) pose.getX());
        if (small) {
            matrix.scale(0.5F);
        }

        return matrix
                .rotateX((float) Math.toRadians(-90.0))
                .rotateY((float) Math.PI)
                .translate(1.0F / 16.0F, 2.0F / 16.0F, -10.0F / 16.0F)
                // ItemDisplayRenderer adds this Y-180 immediately before the
                // selected vanilla item-model context; cancel it here.
                .rotateY((float) Math.PI);
    }

    private static Vector3f rotateY(Vector3f vector, float angle) {
        float sine = (float) Math.sin(angle);
        float cosine = (float) Math.cos(angle);
        float x = cosine * vector.x + sine * vector.z;
        float z = -sine * vector.x + cosine * vector.z;
        return vector.set(x, vector.y, z);
    }

    private static LegacyHandProfile legacyHandProfile(Item.RenderMode mode) {
        return switch (mode) {
            case ITEM -> legacyHandProfile("item");
            case BLOCK -> legacyHandProfile("block");
            case LOW_BLOCK -> legacyHandProfile("lowblock");
            case TOOL -> legacyHandProfile("tool");
            case STANDING -> legacyHandProfile("standing");
            case DROPPED, BANNER, FRAME -> throw new IllegalArgumentException("Unsupported legacy hand mode: " + mode);
        };
    }

    private static LegacyHandProfile legacyHandProfile(String mode) {
        return switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case "item" -> new LegacyHandProfile(0.0F, 0.0F, 0.0F, EulerAngle.ZERO, false);
            case "block" -> new LegacyHandProfile(0.102F, 0.084F, 0.14F,
                    new EulerAngle(357.9, 0.0, 0.0), true);
            case "lowblock" -> new LegacyHandProfile(0.09F, 0.02F, 0.15F,
                    new EulerAngle(357.9, 0.0, 0.0), true);
            case "tool" -> new LegacyHandProfile(-0.3F, -0.26F, -0.1F,
                    new EulerAngle(357.99, 0.0, 300.0), false);
            case "standing" -> new LegacyHandProfile(-0.323F, -0.32F, 0.115F,
                    new EulerAngle(0.0, 4.7, 4.7), false);
            // External API consumers may repurpose the public custom name.
            // Preserve the historical default-item fallback instead of
            // failing the display synchronization task.
            default -> new LegacyHandProfile(0.0F, 0.0F, 0.0F, EulerAngle.ZERO, false);
        };
    }

    private record LegacyHandProfile(float lateral, float vertical, float forward,
                                     EulerAngle pose, boolean diagonal) {
    }


    public static Matrix4f text(DisplayEntity state) {
        Matrix4f matrix = new Matrix4f().identity();
        float scale = state.getTextScale();
        if (state.usesLegacyNameTagGeometry()) {
            /*
             * TextDisplayRenderer offsets a centered single line by one pixel
             * on X and nine pixels on Y before applying its built-in 0.025
             * scale. A vanilla entity name tag has neither offset. Cancel both
             * here; textDisplayLocation supplies the name tag's world-space
             * +0.5 Y attachment separately so pitch does not rotate it.
             */
            matrix.translate(-TEXT_PIXEL_SCALE * scale,
                    -TEXT_PIXEL_SCALE * SINGLE_LINE_BASELINE_PIXELS * scale,
                    0.0F);
        }
        return matrix.scale(scale);
    }

    public static Location textDisplayLocation(DisplayEntity state) {
        return textDisplayLocation(state, state.getLocation());
    }

    public static Location textDisplayLocation(DisplayEntity state, Location logicalLocation) {
        Location location = logicalLocation.clone();
        if (state.usesLegacyNameTagGeometry()) {
            location.add(0.0D, LEGACY_NAME_TAG_WORLD_Y, 0.0D);
        }
        return location;
    }

    public static Matrix4f itemFrame(ItemFrame state) {
        return new Matrix4f()
                .identity()
                .rotateZ((float) Math.toRadians(state.getFrameRotation() * 45.0))
                .scale(0.5F);
    }

    private static String mode(DisplayEntity state) {
        if (state.getCustomName() == null) {
            return "item";
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(state.getCustomName());
        int separator = plain.lastIndexOf('.');
        return (separator < 0 ? plain : plain.substring(separator + 1)).toLowerCase(java.util.Locale.ROOT);
    }

    private static String legacyMode(DisplayEntity state) {
        if (state.getCustomName() == null) {
            return "item";
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(state.getCustomName());
        if (!plain.startsWith("IV.Custom.")) {
            return "item";
        }
        int separator = plain.lastIndexOf('.');
        return plain.substring(separator + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
