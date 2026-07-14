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

package net.minecraft.network.protocol.game;

import net.minecraft.network.protocol.Packet;

public final class ClientboundRemoveEntitiesPacket implements Packet<Object> {

    private final int[] entityIds;

    public ClientboundRemoveEntitiesPacket(int... entityIds) {
        this.entityIds = entityIds.clone();
    }

    public int[] entityIds() {
        return entityIds.clone();
    }
}
