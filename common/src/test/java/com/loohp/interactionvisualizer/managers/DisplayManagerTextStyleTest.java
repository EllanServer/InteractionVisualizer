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

package com.loohp.interactionvisualizer.managers;

import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.EntityHolderTestFactory;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayManagerTextStyleTest {

    @Test
    void hiddenLegacyNameTagKeepsItsTextKindWithoutRenderingText()
            throws ReflectiveOperationException {
        DisplayEntity logical = EntityHolderTestFactory.allocate(DisplayEntity.class);
        logical.useLegacyNameTagStyle();
        logical.setCustomName(Component.text("lectern title"));

        assertTrue(logical.isTextDisplay());
        assertFalse(logical.isCustomNameVisible());
        assertFalse(DisplayManager.shouldRender(logical));

        logical.setCustomNameVisible(true);

        assertTrue(DisplayManager.shouldRender(logical));
    }

    @Test
    void floatingTextUsesNormalDepthTestingWithoutAForcedShadow()
            throws ReflectiveOperationException {
        Map<String, Object> setters = new HashMap<>();
        TextDisplay actual = recordingTextDisplay(setters);
        DisplayEntity logical = EntityHolderTestFactory.allocate(DisplayEntity.class);
        logical.setCustomName(Component.text("blocked by walls"));
        logical.setCustomNameVisible(true);
        logical.useLegacyNameTagStyle();

        DisplayManager.applyTextDisplay(actual, logical);

        assertEquals(false, setters.get("setSeeThrough"));
        assertEquals(false, setters.get("setShadowed"));
        assertEquals(true, setters.get("setDefaultBackground"));
        assertEquals(Integer.MAX_VALUE, setters.get("setLineWidth"));
    }

    @Test
    void itemNameCanDisableWrappingWithoutChangingItsGeometryProfile()
            throws ReflectiveOperationException {
        Map<String, Object> setters = new HashMap<>();
        TextDisplay actual = recordingTextDisplay(setters);
        DisplayEntity logical = EntityHolderTestFactory.allocate(DisplayEntity.class);
        logical.setCustomName(Component.text("a deliberately long item name"));
        logical.setCustomNameVisible(true);
        logical.setUnboundedTextWidth(true);

        DisplayManager.applyTextDisplay(actual, logical);

        assertEquals(Integer.MAX_VALUE, setters.get("setLineWidth"));
        assertFalse(logical.usesLegacyNameTagStyle());
        assertFalse(logical.usesLegacyNameTagGeometry());
    }

    private static TextDisplay recordingTextDisplay(Map<String, Object> setters) {
        return (TextDisplay) Proxy.newProxyInstance(
                TextDisplay.class.getClassLoader(), new Class<?>[] {TextDisplay.class},
                (proxy, method, args) -> {
                    if (args != null && args.length == 1 && method.getName().startsWith("set")) {
                        setters.put(method.getName(), args[0]);
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == byte.class || returnType == short.class
                            || returnType == int.class || returnType == long.class) {
                        return 0;
                    }
                    if (returnType == float.class || returnType == double.class) {
                        return 0.0;
                    }
                    if (returnType == char.class) {
                        return '\0';
                    }
                    return null;
                });
    }
}
