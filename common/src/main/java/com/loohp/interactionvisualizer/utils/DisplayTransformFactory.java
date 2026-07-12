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

import com.loohp.interactionvisualizer.entityholders.BillboardDisplayEntity;
import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.ItemFrame;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.joml.Matrix4f;

/** Centralized transforms replacing armor-stand arm/head pose encoding. */
public final class DisplayTransformFactory {

    private DisplayTransformFactory() {
    }

    public static Matrix4f item(DisplayEntity state) {
        String mode = mode(state);
        Matrix4f matrix = new Matrix4f().identity();

        switch (mode) {
            case "block" -> matrix.translate(0.0F, 0.08F, 0.0F).rotateX((float) Math.toRadians(90)).scale(0.42F);
            case "lowblock" -> matrix.translate(0.0F, 0.02F, 0.0F).rotateX((float) Math.toRadians(90)).scale(0.36F);
            case "tool" -> matrix.rotateZ((float) Math.toRadians(-90)).scale(0.48F);
            case "standing" -> matrix.translate(0.0F, 0.18F, 0.0F).scale(0.5F);
            default -> matrix.scale(state.isSmall() ? 0.42F : 0.5F);
        }
        return matrix;
    }

    public static Matrix4f text(DisplayEntity state) {
        Matrix4f matrix = new Matrix4f().identity();
        if (state instanceof BillboardDisplayEntity billboard && billboard.getRadius() != 0.0) {
            matrix.translate(0.0F, 0.0F, (float) -billboard.getRadius());
        }
        return matrix.scale(0.5F);
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
}
