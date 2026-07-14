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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public record ClientboundAddEntityPacket(
        int id,
        UUID uuid,
        double x,
        double y,
        double z,
        float xRot,
        float yRot,
        EntityType<?> type,
        int data,
        Vec3 movement,
        double yHeadRot) implements Packet<Object> {
}
