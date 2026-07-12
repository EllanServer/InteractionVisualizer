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

package net.minecraft.server.level;

import net.minecraft.server.network.ServerGamePacketListenerImpl;

public final class ServerPlayer {

    public final ServerGamePacketListenerImpl connection;

    public ServerPlayer(ServerGamePacketListenerImpl connection) {
        this.connection = connection;
    }
}
