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

package net.minecraft.network.syncher;

public final class SynchedEntityData {

    private SynchedEntityData() {
    }

    public record DataValue<T>(int id, T value) {

        public static <T> DataValue<T> create(EntityDataAccessor<T> accessor, T value) {
            return new DataValue<>(accessor.id(), value);
        }
    }
}
