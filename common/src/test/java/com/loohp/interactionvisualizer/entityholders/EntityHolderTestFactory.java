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

package com.loohp.interactionvisualizer.entityholders;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only allocation that avoids Paper's server-backed ItemStack factory. */
public final class EntityHolderTestFactory {

    private EntityHolderTestFactory() {
    }

    public static <T extends VisualizerEntity> T allocate(Class<T> type)
            throws ReflectiveOperationException {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
        Object instance = allocateInstance.invoke(unsafeField.get(null), type);

        Field revision = VisualizerEntity.class.getDeclaredField("revision");
        revision.setAccessible(true);
        revision.set(instance, new AtomicInteger(1));
        return type.cast(instance);
    }
}
