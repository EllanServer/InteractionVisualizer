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

package net.minecraft.world.entity;

import net.minecraft.network.syncher.EntityDataAccessor;

public abstract class Display {

    public static final EntityDataAccessor<Integer> DATA_POS_ROT_INTERPOLATION_DURATION_ID =
            new EntityDataAccessor<>(10);
    private static final EntityDataAccessor<Object> DATA_TRANSLATION_ID = new EntityDataAccessor<>(11);
    private static final EntityDataAccessor<Object> DATA_SCALE_ID = new EntityDataAccessor<>(12);
    private static final EntityDataAccessor<Byte> DATA_BILLBOARD_RENDER_CONSTRAINTS_ID =
            new EntityDataAccessor<>(13);

    public enum BillboardConstraints {
        FIXED((byte) 0),
        VERTICAL((byte) 1),
        HORIZONTAL((byte) 2),
        CENTER((byte) 3);

        private final byte id;

        BillboardConstraints(byte id) {
            this.id = id;
        }
    }

    public static final class TextDisplay extends Display {

        public static final byte FLAG_USE_DEFAULT_BACKGROUND = 0x04;

        private static final EntityDataAccessor<Object> DATA_TEXT_ID = new EntityDataAccessor<>(20);
        public static final EntityDataAccessor<Integer> DATA_LINE_WIDTH_ID = new EntityDataAccessor<>(21);
        public static final EntityDataAccessor<Integer> DATA_BACKGROUND_COLOR_ID = new EntityDataAccessor<>(22);
        private static final EntityDataAccessor<Byte> DATA_TEXT_OPACITY_ID = new EntityDataAccessor<>(23);
        private static final EntityDataAccessor<Byte> DATA_STYLE_FLAGS_ID = new EntityDataAccessor<>(24);

        private TextDisplay() {
        }
    }
}
