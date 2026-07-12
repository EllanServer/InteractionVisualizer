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

import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPickupAnimationBridgeTest {

    private static Field bukkitServer;

    @BeforeAll
    static void installTestServer() throws ReflectiveOperationException {
        bukkitServer = Bukkit.class.getDeclaredField("server");
        bukkitServer.setAccessible(true);
        assertNull(bukkitServer.get(null), "another test already installed Bukkit's singleton server");
        bukkitServer.set(null, proxy(Server.class, 0, null));
    }

    @AfterAll
    static void clearTestServer() throws IllegalAccessException {
        bukkitServer.set(null, null);
    }

    @Test
    void resolvesInheritedSendMethodAndPreservesPickupPacketFields() {
        assertTrue(ClientPickupAnimationBridge.initialize(), () ->
                "bridge resolution failed: " + ClientPickupAnimationBridge.initializationFailure());
        assertNull(ClientPickupAnimationBridge.initializationFailure());

        ServerGamePacketListenerImpl connection = new ServerGamePacketListenerImpl();
        Player viewer = proxy(CraftPlayer.class, 17, new ServerPlayer(connection));
        Player collector = proxy(CraftPlayer.class, 93, new ServerPlayer(new ServerGamePacketListenerImpl()));

        ClientPickupAnimationBridge.send(viewer, 41, collector, 64);

        ClientboundTakeItemEntityPacket packet = assertInstanceOf(
                ClientboundTakeItemEntityPacket.class, connection.lastPacket());
        assertEquals(41, packet.itemEntityId());
        assertEquals(93, packet.collectorEntityId());
        assertEquals(64, packet.amount());
    }

    @Test
    void clampsAnInvalidAmountToOne() {
        ServerGamePacketListenerImpl connection = new ServerGamePacketListenerImpl();
        Player viewer = proxy(CraftPlayer.class, 17, new ServerPlayer(connection));
        Player collector = proxy(CraftPlayer.class, 93, new ServerPlayer(new ServerGamePacketListenerImpl()));

        ClientPickupAnimationBridge.send(viewer, 41, collector, 0);

        ClientboundTakeItemEntityPacket packet = assertInstanceOf(
                ClientboundTakeItemEntityPacket.class, connection.lastPacket());
        assertEquals(1, packet.amount());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, int entityId, ServerPlayer handle) {
        return (T) Proxy.newProxyInstance(
                ClientPickupAnimationBridgeTest.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getHandle" -> handle;
                    case "getEntityId" -> entityId;
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Test" + type.getSimpleName() + '[' + entityId + ']';
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        return 0.0D;
    }
}
