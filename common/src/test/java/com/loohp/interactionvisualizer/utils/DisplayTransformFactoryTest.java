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

package com.loohp.interactionvisualizer.utils;

import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.EntityHolderTestFactory;
import com.loohp.interactionvisualizer.entityholders.Item;
import com.loohp.interactionvisualizer.entityholders.VisualizerEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplayTransformFactoryTest {

    @Test
    void textTransformUsesThePerDisplayScale() throws ReflectiveOperationException {
        DisplayEntity text = EntityHolderTestFactory.allocate(DisplayEntity.class);
        text.setTextScale(0.75F);

        Matrix4f transform = DisplayTransformFactory.text(text);

        assertEquals(0.75F, transform.m00(), 1.0E-6F);
        assertEquals(0.75F, transform.m11(), 1.0E-6F);
        assertEquals(0.75F, transform.m22(), 1.0E-6F);
    }

    @Test
    void legacyNameTagTransformCancelsTheTextDisplayGlyphOrigin() throws ReflectiveOperationException {
        DisplayEntity text = EntityHolderTestFactory.allocate(DisplayEntity.class);
        text.useLegacyNameTagStyle();

        Matrix4f transform = DisplayTransformFactory.text(text);
        Vector3f origin = transform.transformPosition(new Vector3f());

        assertEquals(-0.025F, origin.x, 1.0E-6F);
        assertEquals(-0.225F, origin.y, 1.0E-6F);
        assertEquals(0.0F, origin.z, 1.0E-6F);
        assertEquals(1.0F, transform.m00(), 1.0E-6F);
        assertEquals(1.0F, transform.m11(), 1.0E-6F);
        assertEquals(1.0F, transform.m22(), 1.0E-6F);
    }

    @Test
    void geometryOnlyProfileAppliesTheSameSizeAndAnchorCompensation()
            throws ReflectiveOperationException {
        DisplayEntity text = EntityHolderTestFactory.allocate(DisplayEntity.class);
        text.useLegacyNameTagGeometry();

        Matrix4f transform = DisplayTransformFactory.text(text);
        Vector3f origin = transform.transformPosition(new Vector3f());

        assertEquals(-0.025F, origin.x, 1.0E-6F);
        assertEquals(-0.225F, origin.y, 1.0E-6F);
        assertEquals(1.0F, transform.m00(), 1.0E-6F);
        assertEquals(false, text.usesLegacyNameTagStyle());
    }

    @Test
    void legacyNameTagUsesTheVanillaHalfBlockWorldAttachment() throws ReflectiveOperationException {
        DisplayEntity text = EntityHolderTestFactory.allocate(DisplayEntity.class);
        Location logical = new Location(null, 12.5, 64.2, -3.5);
        Field location = VisualizerEntity.class.getDeclaredField("location");
        location.setAccessible(true);
        location.set(text, logical);
        text.useLegacyNameTagStyle();

        Location rendered = DisplayTransformFactory.textDisplayLocation(text);

        assertEquals(64.7, rendered.getY(), 1.0E-9);
        assertEquals(64.2, logical.getY(), 1.0E-9,
                "render compensation must not mutate the logical marker anchor");
    }

    @Test
    void bannerModeMatchesTheLegacySmallHeadTransform() {
        Matrix4f bannerTransform = DisplayTransformFactory.item(Item.RenderMode.BANNER);
        Vector3f bannerOrigin = bannerTransform.transformPosition(new Vector3f());
        assertEquals(-0.078375F, bannerOrigin.y, 1.0E-6F,
                "the light-sampling anchor compensation must preserve the old world position");
        assertEquals(0.46875F, bannerTransform.m00(), 1.0E-6F,
                "the banner must retain the small legacy head scale");
        assertEquals(0.46875F, bannerTransform.m11(), 1.0E-6F,
                "the banner must retain the small legacy head scale");
        assertEquals(0.46875F, bannerTransform.m22(), 1.0E-6F,
                "the banner transform must not mirror the HEAD model");
        assertEquals(ItemDisplay.ItemDisplayTransform.HEAD,
                DisplayTransformFactory.itemDisplayTransform(Item.RenderMode.BANNER),
                "loom banners must use the same vanilla context as head equipment");
        assertEquals(ItemDisplay.ItemDisplayTransform.FIXED,
                DisplayTransformFactory.itemDisplayTransform(Item.RenderMode.ITEM),
                "other item display contexts must remain unchanged");

        Vector3f ordinaryOrigin = DisplayTransformFactory.item(Item.RenderMode.ITEM).transformPosition(new Vector3f());
        assertEquals(0.0F, ordinaryOrigin.y, 1.0E-6F, "the Loom offset must not affect other item displays");
    }
}
