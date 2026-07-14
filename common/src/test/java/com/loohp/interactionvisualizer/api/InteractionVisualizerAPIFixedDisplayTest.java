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

package com.loohp.interactionvisualizer.api;

import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.entityholders.EntityHolderTestFactory;
import com.loohp.interactionvisualizer.entityholders.VisualizerEntity;
import com.loohp.interactionvisualizer.utils.DisplayTransformFactory;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class InteractionVisualizerAPIFixedDisplayTest {

    @Test
    void itemHoldingObjectUsesTheProvidedDirectDisplayAnchorWithoutMutatingIt() {
        Location source = new Location(null, 12.25, 64.75, -3.5, 27.0F, 11.0F);
        Location snapshot = source.clone();

        Location displayLocation = InteractionVisualizerAPI.itemHoldingDisplayLocation(source);

        assertEquals(snapshot, source, "the compatibility API must not rewrite the caller's Location");
        assertLocationEquals(snapshot, displayLocation);

        source.add(20.0, 20.0, 20.0).setYaw(180.0F);
        assertLocationEquals(snapshot, displayLocation);
    }

    @Test
    void holdingModeChangesPreserveWorldCoordinatesAndOnlyAdjustDiagonalYaw() throws ReflectiveOperationException {
        Location anchor = new Location(null, 1.25, 70.0, 4.75, 10.0F, 5.0F);
        DisplayEntity display = testDisplay(anchor);

        assertSame(display, InteractionVisualizerAPI.rotateDisplayEntityItemHoldingObject(
                display, InteractionVisualizerAPI.DisplayEntityHoldingMode.LOWBLOCK));
        assertModeAndAnchor(display, InteractionVisualizerAPI.DisplayEntityHoldingMode.LOWBLOCK, 55.0F);
        assertEquals(InteractionVisualizerAPI.DisplayEntityHoldingMode.LOWBLOCK,
                InteractionVisualizerAPI.getDisplayEntityItemHoldingObjectMode(
                        display, InteractionVisualizerAPI.DisplayEntityHoldingMode.ITEM),
                "the historical extra parameter must remain binary-compatible without changing the result");

        InteractionVisualizerAPI.rotateDisplayEntityItemHoldingObject(
                display, InteractionVisualizerAPI.DisplayEntityHoldingMode.LOWBLOCK);
        assertModeAndAnchor(display, InteractionVisualizerAPI.DisplayEntityHoldingMode.LOWBLOCK, 55.0F);

        InteractionVisualizerAPI.rotateDisplayEntityItemHoldingObject(
                display, InteractionVisualizerAPI.DisplayEntityHoldingMode.TOOL);
        assertModeAndAnchor(display, InteractionVisualizerAPI.DisplayEntityHoldingMode.TOOL, 10.0F);

        InteractionVisualizerAPI.rotateDisplayEntityItemHoldingObject(
                display, InteractionVisualizerAPI.DisplayEntityHoldingMode.STANDING);
        assertModeAndAnchor(display, InteractionVisualizerAPI.DisplayEntityHoldingMode.STANDING, 10.0F);

        InteractionVisualizerAPI.rotateDisplayEntityItemHoldingObject(
                display, InteractionVisualizerAPI.DisplayEntityHoldingMode.ITEM);
        assertModeAndAnchor(display, InteractionVisualizerAPI.DisplayEntityHoldingMode.ITEM, 10.0F);
    }

    @Test
    void itemHoldingCompatibilityProfileUsesTheRightHandContextOnlyWhenExplicitlyMarked()
            throws ReflectiveOperationException {
        DisplayEntity display = testDisplay(new Location(null, 1.0, 2.0, 3.0));
        DisplayEntity ordinary = testPlainDisplay(new Location(null, 1.0, 2.0, 3.0));
        ordinary.setSmall(true);
        ordinary.setCustomName("IV.Custom.Item");

        assertEquals(ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND,
                display.getItemDisplayTransform());
        assertEquals(ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND,
                DisplayTransformFactory.itemDisplayTransform(display));
        assertEquals(ItemDisplay.ItemDisplayTransform.FIXED,
                ordinary.getItemDisplayTransform(),
                "reserved-looking text alone must not opt an ordinary display into the legacy hand profile");

        assertTransformOrigin(display, -0.000386488F, 0.376000009F, -0.080493137F);
    }

    @Test
    void externalCustomNameFallsBackToTheLegacyItemProfile() throws ReflectiveOperationException {
        DisplayEntity display = testDisplay(new Location(null, 1.0, 2.0, 3.0));
        Matrix4f itemProfile = DisplayTransformFactory.item(display);

        for (String externalName : new String[] {
                "External.Integration.Label", "External.Integration.Tool", "my.Block",
                "third.party.LowBlock", "plugin.Standing"
        }) {
            display.setCustomName(externalName);
            assertDoesNotThrow(() -> DisplayTransformFactory.item(display));
            assertEquals(ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND,
                    DisplayTransformFactory.itemDisplayTransform(display));
            assertMatrixProbes(itemProfile, DisplayTransformFactory.item(display), 0.0F, externalName);
        }
    }

    @Test
    void nonZeroPitchMatchesTheIndependentLegacyArmorStandWorldTransform()
            throws ReflectiveOperationException {
        Location anchor = new Location(null, 12.25, 64.75, -3.5, 27.0F, 35.0F);

        for (InteractionVisualizerAPI.DisplayEntityHoldingMode mode
                : InteractionVisualizerAPI.DisplayEntityHoldingMode.values()) {
            DisplayEntity display = testDisplay(anchor);
            InteractionVisualizerAPI.rotateDisplayEntityItemHoldingObject(display, mode);

            Matrix4f actual = renderedItemDisplayWorldTransform(display);
            Matrix4f expected = independentLegacyArmorStandWorldTransform(anchor, mode);
            assertMatrixProbes(expected, actual, 8.0E-6F, mode.toString());
        }
    }

    @Test
    void lockedHoldingDisplayRejectsModeChangesWithoutCorruptingItsTransform()
            throws ReflectiveOperationException {
        DisplayEntity display = testDisplay(new Location(null, 1.25, 70.0, 4.75, 27.0F, 35.0F));
        Location originalLocation = display.getLocation();
        EulerAngle originalPose = display.getRightArmPose();
        int originalRevision = display.cacheCode();
        Matrix4f originalTransform = renderedItemDisplayWorldTransform(display);
        display.setLocked(true);

        for (InteractionVisualizerAPI.DisplayEntityHoldingMode mode
                : InteractionVisualizerAPI.DisplayEntityHoldingMode.values()) {
            assertSame(display, InteractionVisualizerAPI.rotateDisplayEntityItemHoldingObject(display, mode));
            assertEquals(InteractionVisualizerAPI.DisplayEntityHoldingMode.ITEM,
                    InteractionVisualizerAPI.getStandMode(display));
            assertLocationEquals(originalLocation, display.getLocation());
            assertEquals(originalPose, display.getRightArmPose());
            assertEquals(originalRevision, display.cacheCode());
            assertMatrixProbes(originalTransform, renderedItemDisplayWorldTransform(display), 0.0F,
                    "locked " + mode);
        }

        display.setLocked(false);
        InteractionVisualizerAPI.rotateDisplayEntityItemHoldingObject(
                display, InteractionVisualizerAPI.DisplayEntityHoldingMode.LOWBLOCK);
        assertModeAndAnchor(display, InteractionVisualizerAPI.DisplayEntityHoldingMode.LOWBLOCK, 72.0F,
                originalLocation);
    }

    private static void assertModeAndAnchor(DisplayEntity display,
                                            InteractionVisualizerAPI.DisplayEntityHoldingMode mode,
                                            float expectedYaw) {
        assertModeAndAnchor(display, mode, expectedYaw,
                new Location(null, 1.25, 70.0, 4.75, 10.0F, 5.0F));
    }

    private static void assertModeAndAnchor(DisplayEntity display,
                                            InteractionVisualizerAPI.DisplayEntityHoldingMode mode,
                                            float expectedYaw,
                                            Location expectedAnchor) {
        Location location = display.getLocation();
        assertEquals(mode, InteractionVisualizerAPI.getStandMode(display));
        assertEquals(expectedAnchor.getX(), location.getX());
        assertEquals(expectedAnchor.getY(), location.getY());
        assertEquals(expectedAnchor.getZ(), location.getZ());
        assertEquals(expectedYaw, location.getYaw());
        assertEquals(expectedAnchor.getPitch(), location.getPitch());
        assertEquals(ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND,
                DisplayTransformFactory.itemDisplayTransform(display));

        EulerAngle expectedPose = switch (mode) {
            case ITEM -> EulerAngle.ZERO;
            case LOWBLOCK -> new EulerAngle(357.9, 0.0, 0.0);
            case TOOL -> new EulerAngle(357.99, 0.0, 300.0);
            case STANDING -> new EulerAngle(0.0, 4.7, 4.7);
        };
        assertEquals(expectedPose, display.getRightArmPose());

    }

    private static Matrix4f renderedItemDisplayWorldTransform(DisplayEntity display) {
        Location location = display.getLocation();
        return new Matrix4f().identity()
                .rotateYXZ((float) Math.toRadians(-location.getYaw()),
                        (float) Math.toRadians(location.getPitch()), 0.0F)
                .mul(DisplayTransformFactory.item(display))
                // ItemDisplayRenderer applies this immediately before the
                // selected vanilla item-model context.
                .rotateY((float) Math.PI);
    }

    private static Matrix4f independentLegacyArmorStandWorldTransform(
            Location source, InteractionVisualizerAPI.DisplayEntityHoldingMode mode) {
        Vector direction = source.getDirection().normalize();
        Vector offset = legacyRotateAroundY(direction.clone().multiply(0.19), -100.0)
                .add(direction.clone().multiply(-0.11))
                .add(independentLegacyModeOffset(direction, mode));
        float displayYaw = source.getYaw()
                + (mode == InteractionVisualizerAPI.DisplayEntityHoldingMode.LOWBLOCK ? 45.0F : 0.0F);
        EulerAngle pose = switch (mode) {
            case ITEM -> EulerAngle.ZERO;
            case LOWBLOCK -> new EulerAngle(357.9, 0.0, 0.0);
            case TOOL -> new EulerAngle(357.99, 0.0, 300.0);
            case STANDING -> new EulerAngle(0.0, 4.7, 4.7);
        };

        return new Matrix4f().identity()
                .translate((float) offset.getX(), (float) offset.getY(), (float) offset.getZ())
                .rotateY((float) Math.toRadians(180.0F - displayYaw))
                .scale(-1.0F, -1.0F, 1.0F)
                .translate(0.0F, -1.501F, 0.0F)
                .translate(-2.5F / 16.0F, 13.0F / 16.0F, 0.0F)
                .rotateZYX((float) pose.getZ(), (float) pose.getY(), (float) pose.getX())
                .scale(0.5F)
                .rotateX((float) Math.toRadians(-90.0F))
                .rotateY((float) Math.PI)
                .translate(1.0F / 16.0F, 2.0F / 16.0F, -10.0F / 16.0F);
    }

    private static Vector independentLegacyModeOffset(
            Vector direction, InteractionVisualizerAPI.DisplayEntityHoldingMode mode) {
        return switch (mode) {
            case ITEM -> new Vector();
            case LOWBLOCK -> direction.clone().multiply(0.15)
                    .add(legacyRotateAroundY(direction.clone().multiply(0.09), -90.0))
                    .add(new Vector(0.0, 0.02, 0.0));
            case TOOL -> direction.clone().multiply(-0.1)
                    .add(legacyRotateAroundY(direction.clone().multiply(-0.3), -90.0))
                    .add(new Vector(0.0, -0.26, 0.0));
            case STANDING -> direction.clone().multiply(0.115)
                    .add(legacyRotateAroundY(direction.clone().multiply(-0.323), -90.0))
                    .add(new Vector(0.0, -0.32, 0.0));
        };
    }

    private static Vector legacyRotateAroundY(Vector vector, double degrees) {
        double radians = Math.toRadians(degrees);
        double x = vector.getX();
        double z = vector.getZ();
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        return vector.setX(cosine * x - sine * z).setZ(sine * x + cosine * z);
    }

    private static void assertMatrixProbes(Matrix4f expected, Matrix4f actual,
                                           float tolerance, String description) {
        Vector3f[] probes = {
                new Vector3f(),
                new Vector3f(1.0F, 0.0F, 0.0F),
                new Vector3f(0.0F, 1.0F, 0.0F),
                new Vector3f(0.0F, 0.0F, 1.0F)
        };
        for (int index = 0; index < probes.length; index++) {
            Vector3f expectedProbe = expected.transformPosition(new Vector3f(probes[index]));
            Vector3f actualProbe = actual.transformPosition(new Vector3f(probes[index]));
            assertEquals(expectedProbe.x, actualProbe.x, tolerance, description + " probe " + index + " x");
            assertEquals(expectedProbe.y, actualProbe.y, tolerance, description + " probe " + index + " y");
            assertEquals(expectedProbe.z, actualProbe.z, tolerance, description + " probe " + index + " z");
        }
    }

    private static void assertLocationEquals(Location expected, Location actual) {
        assertSame(expected.getWorld(), actual.getWorld());
        assertEquals(expected.getX(), actual.getX());
        assertEquals(expected.getY(), actual.getY());
        assertEquals(expected.getZ(), actual.getZ());
        assertEquals(expected.getYaw(), actual.getYaw());
        assertEquals(expected.getPitch(), actual.getPitch());
    }

    private static DisplayEntity testDisplay(Location location) throws ReflectiveOperationException {
        DisplayEntity display = testPlainDisplay(location);
        display.setSmall(true);
        display.setLegacyRightHandItemTransform(true);
        display.setRightArmPose(EulerAngle.ZERO);
        display.setCustomName("IV.Custom.Item");
        return display;
    }

    private static DisplayEntity testPlainDisplay(Location location) throws ReflectiveOperationException {
        DisplayEntity display = EntityHolderTestFactory.allocate(DisplayEntity.class);
        Field entityLocation = VisualizerEntity.class.getDeclaredField("location");
        entityLocation.setAccessible(true);
        entityLocation.set(display, location.clone());
        return display;
    }

    private static void assertTransformOrigin(DisplayEntity display, float x, float y, float z) {
        Vector3f origin = DisplayTransformFactory.item(display).transformPosition(new Vector3f());
        assertEquals(x, origin.x, 2.0E-6F);
        assertEquals(y, origin.y, 2.0E-6F);
        assertEquals(z, origin.z, 2.0E-6F);
    }
}
