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
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.momirealms.sparrow.heart.util.SelfIncreaseEntityID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Isolated Paper 26.1/26.2 packet bridge for a client-only text display.
 *
 * <p>The entity never enters a world or Paper's entity tracker. Its initial
 * add-entity and complete render metadata packets are sent in one bundle, so
 * the client cannot render a frame with vanilla text-display defaults.</p>
 */
public final class ClientTextDisplayBridge {

    private static final GsonComponentSerializer COMPONENT_SERIALIZER = GsonComponentSerializer.gson();

    private final int entityId;
    private final UUID uuid;

    private ClientTextDisplayBridge(int entityId, UUID uuid) {
        this.entityId = entityId;
        this.uuid = uuid;
    }

    /**
     * Resolves the runtime packet surface without creating an entity.
     *
     * @return whether this Paper runtime exposes the expected 26.1/26.2 surface
     */
    public static boolean initialize() {
        return Holder.RESOLUTION.handles() != null;
    }

    /**
     * Returns the linkage failure captured while probing this runtime.
     */
    public static Throwable initializationFailure() {
        return Holder.RESOLUTION.failure();
    }

    /**
     * Creates a client-only identity using Sparrow Heart's high, process-wide
     * entity ID range. Synchronization closes the race in Sparrow's incrementer
     * when displays are allocated concurrently.
     */
    public static ClientTextDisplayBridge create() {
        requireHandles();
        int entityId;
        synchronized (SelfIncreaseEntityID.class) {
            entityId = SelfIncreaseEntityID.getAndIncrease();
        }
        return new ClientTextDisplayBridge(entityId, UUID.randomUUID());
    }

    public int entityId() {
        return entityId;
    }

    /**
     * Spawns this client-only text display for one viewer.
     */
    public void spawn(Player viewer, Location location, Component text) {
        Objects.requireNonNull(location, "location");
        Handles handles = checkedHandles(viewer);
        try {
            Object addEntityPacket = handles.addEntityPacketConstructor().newInstance(
                    entityId,
                    uuid,
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    location.getPitch(),
                    location.getYaw(),
                    handles.textDisplayEntityType(),
                    0,
                    handles.zeroMovement(),
                    (double) location.getYaw());
            Object metadataPacket = createMetadataPacket(handles, text);
            Object bundlePacket = handles.bundlePacketConstructor().newInstance(
                    List.of(addEntityPacket, metadataPacket));
            send(handles, viewer, bundlePacket, "spawn the client text display");
        } catch (ReflectiveOperationException exception) {
            throw operationFailure("spawn the client text display", exception);
        }
    }

    /**
     * Re-sends the complete metadata profile with updated text. Reasserting the
     * full profile also makes this safe after client resource reloads.
     */
    public void updateMetaData(Player viewer, Component text) {
        Handles handles = checkedHandles(viewer);
        try {
            send(handles, viewer, createMetadataPacket(handles, text),
                    "update the client text display metadata");
        } catch (ReflectiveOperationException exception) {
            throw operationFailure("update the client text display metadata", exception);
        }
    }

    /**
     * Moves this client-only entity with the same absolute teleport payload as
     * Sparrow Heart, without wrapping the single packet in bundle delimiters.
     */
    public void teleport(Player viewer, Location location) {
        Objects.requireNonNull(location, "location");
        Handles handles = checkedHandles(viewer);
        try {
            Object position = handles.vec3Constructor().newInstance(
                    location.getX(), location.getY(), location.getZ());
            Object change = handles.positionMoveRotationConstructor().newInstance(
                    position,
                    handles.zeroMovement(),
                    location.getYaw(),
                    location.getPitch());
            Object packet = handles.teleportEntityPacketConstructor().newInstance(
                    entityId, change, Set.of(), false);
            send(handles, viewer, packet, "teleport the client text display");
        } catch (ReflectiveOperationException exception) {
            throw operationFailure("teleport the client text display", exception);
        }
    }

    /**
     * Removes this client-only entity for one viewer.
     */
    public void destroy(Player viewer) {
        Handles handles = checkedHandles(viewer);
        try {
            Object packet = handles.removeEntitiesPacketConstructor().newInstance(
                    (Object) new int[]{entityId});
            send(handles, viewer, packet, "destroy the client text display");
        } catch (ReflectiveOperationException exception) {
            throw operationFailure("destroy the client text display", exception);
        }
    }

    private Object createMetadataPacket(Handles handles, Component text)
            throws ReflectiveOperationException {
        Objects.requireNonNull(text, "text");
        String json = COMPONENT_SERIALIZER.serialize(text);
        Object vanillaComponent = handles.componentFromJson().invoke(null, json);
        if (vanillaComponent == null) {
            throw new IllegalStateException("CraftChatMessage.fromJSON returned null");
        }

        List<Object> values = new ArrayList<>(handles.renderMetadata().size() + 1);
        values.addAll(handles.renderMetadata());
        values.add(dataValue(handles, handles.textAccessor(), vanillaComponent));
        return handles.metadataPacketConstructor().newInstance(entityId, values);
    }

    private static Object dataValue(Handles handles, Object accessor, Object value)
            throws ReflectiveOperationException {
        return handles.dataValueCreate().invoke(null, accessor, value);
    }

    private static void send(Handles handles, Player viewer, Object packet, String operation)
            throws ReflectiveOperationException {
        try {
            Object serverPlayer = handles.getHandle().invoke(viewer);
            Object connection = handles.connection().get(serverPlayer);
            handles.sendPacket().invoke(connection, packet);
        } catch (InvocationTargetException exception) {
            throw operationFailure(operation, exception);
        }
    }

    private static Handles checkedHandles(Player viewer) {
        Objects.requireNonNull(viewer, "viewer");
        Handles handles = requireHandles();
        if (!handles.craftPlayerClass().isInstance(viewer)) {
            throw new IllegalArgumentException("Unsupported Player implementation: " + viewer.getClass().getName());
        }
        return handles;
    }

    private static Handles requireHandles() {
        Handles handles = Holder.RESOLUTION.handles();
        if (handles == null) {
            throw new IllegalStateException("The client text display packet bridge is unavailable",
                    Holder.RESOLUTION.failure());
        }
        return handles;
    }

    private static IllegalStateException operationFailure(String operation, ReflectiveOperationException exception) {
        Throwable cause = exception instanceof InvocationTargetException invocationTargetException
                ? invocationTargetException.getCause()
                : exception;
        if (cause instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (cause instanceof LinkageError linkageError) {
            return new IllegalStateException("The client text display packet linkage failed while trying to "
                    + operation, linkageError);
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Failed to " + operation, cause);
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
            Method sendPacket = connection.getType().getMethod("send", packetClass);

            Class<?> entityTypeClass = Class.forName(
                    "net.minecraft.world.entity.EntityType", false, serverLoader);
            Object textDisplayEntityType = entityTypeClass.getField("TEXT_DISPLAY").get(null);
            Class<?> vec3Class = Class.forName(
                    "net.minecraft.world.phys.Vec3", false, serverLoader);
            Constructor<?> vec3Constructor = vec3Class.getConstructor(
                    double.class, double.class, double.class);
            Object zeroMovement = vec3Class.getField("ZERO").get(null);

            Class<?> positionMoveRotationClass = Class.forName(
                    "net.minecraft.world.entity.PositionMoveRotation", false, serverLoader);
            Constructor<?> positionMoveRotationConstructor = positionMoveRotationClass.getConstructor(
                    vec3Class, vec3Class, float.class, float.class);
            Class<?> teleportEntityPacketClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket", false, serverLoader);
            Constructor<?> teleportEntityPacketConstructor = teleportEntityPacketClass.getConstructor(
                    int.class, positionMoveRotationClass, Set.class, boolean.class);

            Class<?> addEntityPacketClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundAddEntityPacket", false, serverLoader);
            Constructor<?> addEntityPacketConstructor = addEntityPacketClass.getConstructor(
                    int.class, UUID.class, double.class, double.class, double.class,
                    float.class, float.class, entityTypeClass, int.class, vec3Class, double.class);

            Class<?> metadataPacketClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket", false, serverLoader);
            Constructor<?> metadataPacketConstructor = metadataPacketClass.getConstructor(int.class, List.class);
            Class<?> bundlePacketClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundBundlePacket", false, serverLoader);
            Constructor<?> bundlePacketConstructor = bundlePacketClass.getConstructor(Iterable.class);
            Class<?> removePacketClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket", false, serverLoader);
            Constructor<?> removeEntitiesPacketConstructor = removePacketClass.getConstructor(int[].class);

            Class<?> accessorClass = Class.forName(
                    "net.minecraft.network.syncher.EntityDataAccessor", false, serverLoader);
            Class<?> dataValueClass = Class.forName(
                    "net.minecraft.network.syncher.SynchedEntityData$DataValue", false, serverLoader);
            Method dataValueCreate = dataValueClass.getMethod("create", accessorClass, Object.class);

            Class<?> displayClass = Class.forName(
                    "net.minecraft.world.entity.Display", false, serverLoader);
            Class<?> textDisplayClass = Class.forName(
                    "net.minecraft.world.entity.Display$TextDisplay", false, serverLoader);
            Object positionInterpolationAccessor = accessor(
                    displayClass, "DATA_POS_ROT_INTERPOLATION_DURATION_ID", accessorClass);
            Object translationAccessor = accessor(displayClass, "DATA_TRANSLATION_ID", accessorClass);
            Object scaleAccessor = accessor(displayClass, "DATA_SCALE_ID", accessorClass);
            Object billboardAccessor = accessor(
                    displayClass, "DATA_BILLBOARD_RENDER_CONSTRAINTS_ID", accessorClass);
            Object textAccessor = accessor(textDisplayClass, "DATA_TEXT_ID", accessorClass);
            Object lineWidthAccessor = accessor(textDisplayClass, "DATA_LINE_WIDTH_ID", accessorClass);
            Object backgroundAccessor = accessor(
                    textDisplayClass, "DATA_BACKGROUND_COLOR_ID", accessorClass);
            Object opacityAccessor = accessor(textDisplayClass, "DATA_TEXT_OPACITY_ID", accessorClass);
            Object styleFlagsAccessor = accessor(textDisplayClass, "DATA_STYLE_FLAGS_ID", accessorClass);

            Class<?> vector3fClass = Class.forName("org.joml.Vector3f", false, serverLoader);
            Constructor<?> vector3fConstructor = vector3fClass.getConstructor(
                    float.class, float.class, float.class);

            Class<?> billboardClass = Class.forName(
                    "net.minecraft.world.entity.Display$BillboardConstraints", false, serverLoader);
            Object center = billboardClass.getField("CENTER").get(null);
            Field billboardId = billboardClass.getDeclaredField("id");
            makeAccessible(billboardId);
            byte centerId = ((Number) billboardId.get(center)).byteValue();
            byte defaultBackground = ((Number) textDisplayClass
                    .getField("FLAG_USE_DEFAULT_BACKGROUND").get(null)).byteValue();

            Class<?> craftChatMessageClass = Class.forName(
                    "org.bukkit.craftbukkit.util.CraftChatMessage", false, serverLoader);
            Method componentFromJson = craftChatMessageClass.getMethod("fromJSON", String.class);

            List<Object> renderMetadata = List.of(
                    dataValue(dataValueCreate, positionInterpolationAccessor, 3),
                    dataValue(dataValueCreate, translationAccessor,
                            vector3fConstructor.newInstance(-0.025F, -0.225F, 0.0F)),
                    dataValue(dataValueCreate, scaleAccessor,
                            vector3fConstructor.newInstance(1.0F, 1.0F, 1.0F)),
                    dataValue(dataValueCreate, billboardAccessor, centerId),
                    dataValue(dataValueCreate, lineWidthAccessor, Integer.MAX_VALUE),
                    dataValue(dataValueCreate, backgroundAccessor, 0),
                    dataValue(dataValueCreate, opacityAccessor, (byte) -1),
                    dataValue(dataValueCreate, styleFlagsAccessor, defaultBackground));

            return new Resolution(new Handles(
                    craftPlayerClass,
                    getHandle,
                    connection,
                    sendPacket,
                    addEntityPacketConstructor,
                    metadataPacketConstructor,
                    bundlePacketConstructor,
                    removeEntitiesPacketConstructor,
                    dataValueCreate,
                    componentFromJson,
                    textDisplayEntityType,
                    vec3Constructor,
                    zeroMovement,
                    positionMoveRotationConstructor,
                    teleportEntityPacketConstructor,
                    textAccessor,
                    renderMetadata), null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return new Resolution(null, exception);
        }
    }

    private static Object accessor(Class<?> owner, String name, Class<?> accessorClass)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        makeAccessible(field);
        Object accessor = field.get(null);
        if (!accessorClass.isInstance(accessor)) {
            throw new IllegalStateException(owner.getName() + '.' + name
                    + " is not an EntityDataAccessor");
        }
        return accessor;
    }

    private static void makeAccessible(Field field) {
        if (!field.trySetAccessible()) {
            throw new IllegalStateException("Unable to access "
                    + field.getDeclaringClass().getName() + '.' + field.getName());
        }
    }

    private static Object dataValue(Method create, Object accessor, Object value)
            throws ReflectiveOperationException {
        return create.invoke(null, accessor, value);
    }

    private record Handles(
            Class<?> craftPlayerClass,
            Method getHandle,
            Field connection,
            Method sendPacket,
            Constructor<?> addEntityPacketConstructor,
            Constructor<?> metadataPacketConstructor,
            Constructor<?> bundlePacketConstructor,
            Constructor<?> removeEntitiesPacketConstructor,
            Method dataValueCreate,
            Method componentFromJson,
            Object textDisplayEntityType,
            Constructor<?> vec3Constructor,
            Object zeroMovement,
            Constructor<?> positionMoveRotationConstructor,
            Constructor<?> teleportEntityPacketConstructor,
            Object textAccessor,
            List<Object> renderMetadata) {
    }

    private record Resolution(Handles handles, Throwable failure) {
    }

    private static final class Holder {

        private static final Resolution RESOLUTION = resolve();
    }

}
