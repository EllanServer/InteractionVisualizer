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

import com.loohp.interactionvisualizer.InteractionVisualizer;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockDisplayFlagIsolationTest {

    @Test
    void disabledEventDrivenModeDoesNotInspectMovedInventoryLocations() throws ReflectiveOperationException {
        boolean previous = InteractionVisualizer.eventDrivenBlockUpdates;
        AtomicInteger inventoryCalls = new AtomicInteger();
        Inventory inventory = (Inventory) Proxy.newProxyInstance(
                Inventory.class.getClassLoader(), new Class<?>[]{Inventory.class}, (proxy, method, arguments) -> {
                    inventoryCalls.incrementAndGet();
                    throw new AssertionError("Disabled event-driven mode inspected inventory via " + method.getName());
                });

        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);

        try {
            InteractionVisualizer.eventDrivenBlockUpdates = false;
            for (Class<?> displayType : List.of(
                    FurnaceDisplay.class, BlastFurnaceDisplay.class, SmokerDisplay.class)) {
                Object display = allocateInstance.invoke(unsafe, displayType);
                Method markDirty = displayType.getDeclaredMethod("markDirty", Inventory.class);
                markDirty.setAccessible(true);
                markDirty.invoke(display, inventory);
            }
            assertEquals(0, inventoryCalls.get());
        } finally {
            InteractionVisualizer.eventDrivenBlockUpdates = previous;
        }
    }
}
