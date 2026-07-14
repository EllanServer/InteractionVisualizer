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

package com.loohp.interactionvisualizer.api.events;

import com.loohp.interactionvisualizer.objectholders.TileEntity.TileEntityType;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Called when a previously indexed tile entity of the same type becomes active again. */
public class TileEntityActivatedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Block block;
    private final TileEntityType type;

    public TileEntityActivatedEvent(Block block, TileEntityType type) {
        this.block = block;
        this.type = type;
    }

    public Block getBlock() {
        return block;
    }

    public TileEntityType getTileEntityType() {
        return type;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

}
