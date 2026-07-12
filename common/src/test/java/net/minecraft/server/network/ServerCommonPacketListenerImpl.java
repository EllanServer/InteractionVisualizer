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

package net.minecraft.server.network;

import net.minecraft.network.protocol.Packet;

public class ServerCommonPacketListenerImpl {

    private Packet<?> lastPacket;

    public void send(Packet<?> packet) {
        lastPacket = packet;
    }

    public Packet<?> lastPacket() {
        return lastPacket;
    }
}
