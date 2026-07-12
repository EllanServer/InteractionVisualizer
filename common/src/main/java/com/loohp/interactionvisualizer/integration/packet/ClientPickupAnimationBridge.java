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

package com.loohp.interactionvisualizer.integration.packet;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Isolated Paper 26.1/26.2 bridge for Minecraft's client-generated pickup animation.
 *
 * <p>Paper does not expose the take-item packet in its API. Public reflection
 * keeps the rest of the project free of direct NMS linkage while retaining a
 * clean fallback if a future Paper build changes the packet surface.</p>
 */
public final class ClientPickupAnimationBridge {

    private ClientPickupAnimationBridge() {
    }

    public static boolean initialize() {
        return Holder.RESOLUTION.handles() != null;
    }

    public static Throwable initializationFailure() {
        return Holder.RESOLUTION.failure();
    }

    public static void send(Player viewer, int itemEntityId, Player collector, int amount) {
        Handles handles = Holder.RESOLUTION.handles();
        if (handles == null) {
            throw new IllegalStateException("The client pickup packet bridge is unavailable",
                    Holder.RESOLUTION.failure());
        }
        if (!handles.craftPlayerClass().isInstance(viewer)) {
            throw new IllegalArgumentException("Unsupported Player implementation: " + viewer.getClass().getName());
        }

        try {
            Object packet = handles.packetConstructor().newInstance(
                    itemEntityId, collector.getEntityId(), Math.max(1, amount));
            Object serverPlayer = handles.getHandle().invoke(viewer);
            Object connection = handles.connection().get(serverPlayer);
            handles.sendPacket().invoke(connection, packet);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof LinkageError linkageError) {
                throw new IllegalStateException("The client pickup packet linkage failed", linkageError);
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Failed to send the client pickup packet", cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to send the client pickup packet", exception);
        }
    }

    private static Resolution resolve() {
        try {
            ClassLoader serverLoader = Bukkit.getServer().getClass().getClassLoader();
            Class<?> craftPlayerClass = Class.forName(
                    "org.bukkit.craftbukkit.entity.CraftPlayer", false, serverLoader);
            Method getHandle = craftPlayerClass.getMethod("getHandle");
            Field connection = getHandle.getReturnType().getField("connection");

            Class<?> packetClass = Class.forName(
                    "net.minecraft.network.protocol.Packet", false, serverLoader);
            Class<?> takePacketClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket", false, serverLoader);
            Constructor<?> packetConstructor = takePacketClass.getConstructor(
                    int.class, int.class, int.class);
            Method sendPacket = connection.getType().getMethod("send", packetClass);

            return new Resolution(new Handles(
                    craftPlayerClass, packetConstructor, getHandle, connection, sendPacket), null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return new Resolution(null, exception);
        }
    }

    private record Handles(Class<?> craftPlayerClass, Constructor<?> packetConstructor,
                           Method getHandle, Field connection, Method sendPacket) {
    }

    private record Resolution(Handles handles, Throwable failure) {
    }

    private static final class Holder {

        private static final Resolution RESOLUTION = resolve();
    }
}
