package net.minecraft.network.protocol.game;

import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;

import java.util.Set;

public record ClientboundTeleportEntityPacket(
        int id,
        PositionMoveRotation change,
        Set<Relative> relatives,
        boolean onGround) implements Packet<Object> {
}
