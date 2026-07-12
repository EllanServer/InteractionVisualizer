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

package com.loohp.interactionvisualizer.integration;

final class CustomContentBridgeLoader {

    private CustomContentBridgeLoader() {
    }

    static CustomContentBridge load(ClassLoader classLoader, String sentinelClass, String implementationClass)
            throws ReflectiveOperationException {
        Class.forName(sentinelClass, false, classLoader);
        return Class.forName(implementationClass, true, classLoader)
                .asSubclass(CustomContentBridge.class)
                .getDeclaredConstructor()
                .newInstance();
    }
}
