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

import net.kyori.adventure.text.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTextDisplayBridgeTest {

    private static Field bukkitServer;

    @BeforeAll
    static void installTestServer() throws ReflectiveOperationException {
        bukkitServer = Bukkit.class.getDeclaredField("server");
        bukkitServer.setAccessible(true);
        assertNull(bukkitServer.get(null), "another test already installed Bukkit's singleton server");
        bukkitServer.set(null, proxy(Server.class, null));
    }

    @AfterAll
    static void clearTestServer() throws IllegalAccessException {
        bukkitServer.set(null, null);
    }

    @Test
    void bundlesSpawnWithTheCompleteLegacyNameTagProfile() {
        assertTrue(ClientTextDisplayBridge.initialize(), () ->
                "bridge resolution failed: " + ClientTextDisplayBridge.initializationFailure());
        assertNull(ClientTextDisplayBridge.initializationFailure());

        ClientTextDisplayBridge display = ClientTextDisplayBridge.create();
        ClientTextDisplayBridge another = ClientTextDisplayBridge.create();
        assertNotEquals(display.entityId(), another.entityId());

        ServerGamePacketListenerImpl connection = new ServerGamePacketListenerImpl();
        Player viewer = proxy(CraftPlayer.class, new ServerPlayer(connection));
        Location location = new Location(null, 1.25, 2.5, -3.75, 90.0F, 12.0F);

        display.spawn(viewer, location, Component.text("Hello"));

        ClientboundBundlePacket bundle = assertInstanceOf(
                ClientboundBundlePacket.class, connection.lastPacket());
        assertEquals(2, bundle.subPackets().size());
        ClientboundAddEntityPacket add = assertInstanceOf(
                ClientboundAddEntityPacket.class, bundle.subPackets().get(0));
        assertEquals(display.entityId(), add.id());
        assertNotNull(add.uuid());
        assertEquals(1.25, add.x());
        assertEquals(2.5, add.y());
        assertEquals(-3.75, add.z());
        assertEquals(12.0F, add.xRot());
        assertEquals(90.0F, add.yRot());
        assertEquals(90.0, add.yHeadRot());
        assertEquals("text_display", add.type().key());
        assertSame(Vec3.ZERO, add.movement());

        ClientboundSetEntityDataPacket metadata = assertInstanceOf(
                ClientboundSetEntityDataPacket.class, bundle.subPackets().get(1));
        assertEquals(display.entityId(), metadata.id());
        assertMetadata(metadata, "Hello");
    }

    @Test
    void updatesMetadataAndDestroysTheSameClientEntity() {
        ClientTextDisplayBridge display = ClientTextDisplayBridge.create();
        ServerGamePacketListenerImpl connection = new ServerGamePacketListenerImpl();
        Player viewer = proxy(CraftPlayer.class, new ServerPlayer(connection));

        display.updateMetaData(viewer, Component.text("Updated"));

        ClientboundSetEntityDataPacket metadata = assertInstanceOf(
                ClientboundSetEntityDataPacket.class, connection.lastPacket());
        assertEquals(display.entityId(), metadata.id());
        assertMetadata(metadata, "Updated");

        Location destination = new Location(null, -4.5, 17.25, 8.0, -35.0F, 22.5F);
        display.teleport(viewer, destination);

        ClientboundTeleportEntityPacket teleport = assertInstanceOf(
                ClientboundTeleportEntityPacket.class, connection.lastPacket());
        assertEquals(display.entityId(), teleport.id());
        assertEquals(new Vec3(-4.5, 17.25, 8.0), teleport.change().position());
        assertSame(Vec3.ZERO, teleport.change().deltaMovement());
        assertEquals(-35.0F, teleport.change().yRot());
        assertEquals(22.5F, teleport.change().xRot());
        assertTrue(teleport.relatives().isEmpty());
        assertFalse(teleport.onGround());

        display.destroy(viewer);

        ClientboundRemoveEntitiesPacket remove = assertInstanceOf(
                ClientboundRemoveEntitiesPacket.class, connection.lastPacket());
        assertArrayEquals(new int[]{display.entityId()}, remove.entityIds());
    }

    private static void assertMetadata(ClientboundSetEntityDataPacket packet, String expectedText) {
        assertEquals(9, packet.packedItems().size());
        Map<Integer, SynchedEntityData.DataValue<?>> values = packet.packedItems().stream()
                .collect(Collectors.toMap(SynchedEntityData.DataValue::id, Function.identity()));

        assertEquals(3, values.get(10).value());
        Vector3f translation = assertInstanceOf(Vector3f.class, values.get(11).value());
        assertEquals(-0.025F, translation.x());
        assertEquals(-0.225F, translation.y());
        assertEquals(0.0F, translation.z());
        Vector3f scale = assertInstanceOf(Vector3f.class, values.get(12).value());
        assertEquals(1.0F, scale.x());
        assertEquals(1.0F, scale.y());
        assertEquals(1.0F, scale.z());
        assertEquals((byte) 3, values.get(13).value());
        assertEquals(Integer.MAX_VALUE, values.get(21).value());
        assertEquals(0, values.get(22).value());
        assertEquals((byte) -1, values.get(23).value());
        byte flags = assertInstanceOf(Byte.class, values.get(24).value());
        assertEquals((byte) 0x04, flags);
        assertFalse((flags & 0x01) != 0, "shadow must stay disabled");
        assertFalse((flags & 0x02) != 0, "see-through must stay disabled");

        net.minecraft.network.chat.Component text = assertInstanceOf(
                net.minecraft.network.chat.Component.class, values.get(20).value());
        assertTrue(text.json().contains(expectedText));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, ServerPlayer handle) {
        return (T) Proxy.newProxyInstance(
                ClientTextDisplayBridgeTest.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getHandle" -> handle;
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Test" + type.getSimpleName();
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
