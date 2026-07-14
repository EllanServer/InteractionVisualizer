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
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.bukkit.entity.ItemDisplay;
import org.junit.jupiter.api.Test;

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
    void bannerModePlacesTheGuiRenderedBannerAboveTheLoom() {
        Matrix4f bannerTransform = DisplayTransformFactory.item(Item.RenderMode.BANNER);
        Vector3f bannerOrigin = bannerTransform.transformPosition(new Vector3f());
        assertEquals(0.5F, bannerOrigin.y, 1.0E-6F, "the banner must stand on the loom after its light-sampling origin is moved above the block");
        assertEquals(0.8F, bannerTransform.m00(), 1.0E-6F, "the GUI-rendered banner must retain its tested width");
        assertEquals(0.8F, bannerTransform.m11(), 1.0E-6F, "the GUI-rendered banner must retain its tested height");
        assertEquals(ItemDisplay.ItemDisplayTransform.GUI,
                DisplayTransformFactory.itemDisplayTransform(Item.RenderMode.BANNER),
                "the banner special renderer is broken in the HEAD context on Minecraft 26.1");
        assertEquals(ItemDisplay.ItemDisplayTransform.FIXED,
                DisplayTransformFactory.itemDisplayTransform(Item.RenderMode.ITEM),
                "other item display contexts must remain unchanged");

        Vector3f ordinaryOrigin = DisplayTransformFactory.item(Item.RenderMode.ITEM).transformPosition(new Vector3f());
        assertEquals(0.0F, ordinaryOrigin.y, 1.0E-6F, "the Loom offset must not affect other item displays");
    }
}
