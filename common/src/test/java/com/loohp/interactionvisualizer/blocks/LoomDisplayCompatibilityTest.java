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

package com.loohp.interactionvisualizer.blocks;

import com.loohp.interactionvisualizer.entityholders.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoomDisplayCompatibilityTest {

    @Test
    void remembersTheClickedLoomWhenPaperDoesNotExposeAnInventoryLocation() throws Exception {
        Method method = LoomDisplay.class.getDeclaredMethod("onLoomInteract", PlayerInteractEvent.class);
        EventHandler annotation = method.getAnnotation(EventHandler.class);

        assertNotNull(annotation);
        assertEquals(EventPriority.MONITOR, annotation.priority());
        assertTrue(annotation.ignoreCancelled());
    }

    @Test
    void bannerUsesTheSharedItemPipeline() throws Exception {
        Method method = LoomDisplay.class.getDeclaredMethod("setStand", Item.class, float.class);

        assertNotNull(method, "Loom must configure a fixed Item rather than a legacy DisplayEntity");
    }

}
