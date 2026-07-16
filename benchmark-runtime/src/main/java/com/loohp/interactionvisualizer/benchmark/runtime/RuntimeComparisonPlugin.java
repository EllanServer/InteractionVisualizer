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

package com.loohp.interactionvisualizer.benchmark.runtime;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Furnace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Disposable benchmark driver shared by the official upstream artifact and the
 * rewritten candidate. It intentionally has no compile-time dependency on
 * InteractionVisualizer so the same helper JAR can measure both targets.
 */
public final class RuntimeComparisonPlugin extends JavaPlugin implements Listener {

    private static final int MAX_TICK_SAMPLES = 72_000;
    private static final int MAX_SCENE_SIZE = 4_096;
    private static final String ITEM_TAG = "iv_runtime_compare";
    private static final Material[] BLOCK_PATTERN = {
            Material.FURNACE,
            Material.BLAST_FURNACE,
            Material.SMOKER,
            Material.BEEHIVE,
            Material.BEE_NEST
    };
    private static final double TILE_TRACKING_REFRESH_BLOCKS = 64.0D;

    private final double[] tickDurations = new double[MAX_TICK_SAMPLES];
    private final List<Block> sceneBlocks = new ArrayList<>();
    private boolean collecting;
    private boolean skipNextTickSample;
    private int tickSamples;
    private int boundaryTickSamplesDiscarded;
    private long droppedTickSamples;
    private long startedNanos;
    private String label = "";
    private String variant = "";
    private String scenario = "";
    private int requestedSceneSize;
    private String observer = "";
    private RequestedFlags requestedFlags = RequestedFlags.disabled();
    private EffectiveFlags effectiveFlags = EffectiveFlags.unsupportedLegacy();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("ivcompare"), "ivcompare command").setExecutor(this);
    }

    @Override
    public void onDisable() {
        collecting = false;
        clearScene();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /ivcompare <setup|start|stop|clear|status>");
            return true;
        }
        try {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "setup" -> setup(sender, args);
                case "start" -> start(sender, args);
                case "stop" -> stop(sender);
                case "clear" -> clear(sender);
                case "status" -> status(sender);
                default -> false;
            };
        } catch (RuntimeException | IOException exception) {
            getLogger().severe("Runtime comparison command failed: " + exception.getMessage());
            exception.printStackTrace();
            sender.sendMessage("Runtime comparison command failed: " + exception.getMessage());
            return true;
        }
    }

    private boolean setup(CommandSender sender, String[] args) {
        if (args.length != 4) {
            sender.sendMessage("Usage: /ivcompare setup <dropped-items|block-active> <count> <player>");
            return true;
        }
        if (collecting) {
            throw new IllegalStateException("sampling is active");
        }
        String requestedScenario = args[1].toLowerCase(Locale.ROOT);
        if (!requestedScenario.equals("dropped-items") && !requestedScenario.equals("block-active")) {
            throw new IllegalArgumentException("unsupported scenario: " + requestedScenario);
        }
        int count = Integer.parseInt(args[2]);
        if (count < 1 || count > MAX_SCENE_SIZE) {
            throw new IllegalArgumentException("count must be between 1 and " + MAX_SCENE_SIZE);
        }
        Player player = Objects.requireNonNull(Bukkit.getPlayerExact(args[3]), "observer is not online");
        clearScene();

        World world = player.getWorld();
        Location center = new Location(world, 0.5D, 82.0D, 0.5D, 0.0F, 35.0F);
        player.setGameMode(GameMode.SPECTATOR);
        if (!player.teleport(center)) {
            throw new IllegalStateException("failed to position the observer");
        }
        if (requestedScenario.equals("dropped-items")) {
            createDroppedItems(world, count);
        } else {
            createActiveBlocks(world, count);
            refreshTileEntityTracking(player, center);
        }

        scenario = requestedScenario;
        requestedSceneSize = count;
        observer = player.getName();
        int actual = sceneSize(requestedScenario);
        if (actual != count) {
            throw new IllegalStateException("scene count mismatch: expected " + count + ", found " + actual);
        }
        String record = String.format(Locale.ROOT,
                "IV_COMPARE_SCENE state=ready scenario=%s count=%d player=%s",
                scenario, actual, observer);
        getLogger().info(record);
        sender.sendMessage(record);
        return true;
    }

    private boolean start(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage("Usage: /ivcompare start <label> <A|B>");
            return true;
        }
        if (collecting) {
            throw new IllegalStateException("sampling is already active");
        }
        if (scenario.isEmpty() || requestedSceneSize == 0) {
            throw new IllegalStateException("scene is not ready");
        }
        Plugin target = Objects.requireNonNull(
                Bukkit.getPluginManager().getPlugin("InteractionVisualizer"),
                "InteractionVisualizer target is missing");
        if (!target.isEnabled()) {
            throw new IllegalStateException("InteractionVisualizer target is disabled");
        }
        String requestedVariant = args[2].toUpperCase(Locale.ROOT);
        if (!requestedVariant.equals("A") && !requestedVariant.equals("B")) {
            throw new IllegalArgumentException("variant must be A or B");
        }
        label = sanitize(args[1]);
        variant = requestedVariant;
        requestedFlags = readRequestedFlags(target);
        effectiveFlags = inspectEffectiveFlags(target.getClass());
        assertOptimizationProfile(variant, requestedFlags, effectiveFlags);
        tickSamples = 0;
        boundaryTickSamplesDiscarded = 0;
        droppedTickSamples = 0L;
        startedNanos = System.nanoTime();
        skipNextTickSample = true;
        collecting = true;
        String record = "IV_COMPARE_START label=" + label + " variant=" + variant;
        getLogger().info(record);
        sender.sendMessage(record);
        return true;
    }

    private boolean stop(CommandSender sender) throws IOException {
        if (!collecting) {
            throw new IllegalStateException("sampling is not active");
        }
        collecting = false;
        long elapsedNanos = Math.max(1L, System.nanoTime() - startedNanos);
        Snapshot snapshot = snapshot(elapsedNanos);
        Path results = getDataFolder().toPath().resolve("results");
        Files.createDirectories(results);
        Path output = results.resolve(label + ".json");
        Files.writeString(output, snapshot.json() + System.lineSeparator(), StandardCharsets.UTF_8);
        getLogger().info("IV_COMPARE " + snapshot.json());
        sender.sendMessage("IV_COMPARE_STOP label=" + label + " samples=" + tickSamples);
        return true;
    }

    private boolean clear(CommandSender sender) {
        if (collecting) {
            throw new IllegalStateException("sampling is active");
        }
        clearScene();
        String record = "IV_COMPARE_SCENE state=cleared";
        getLogger().info(record);
        sender.sendMessage(record);
        return true;
    }

    private boolean status(CommandSender sender) {
        int actual = scenario.isEmpty() ? 0 : sceneSize(scenario);
        Plugin target = Bukkit.getPluginManager().getPlugin("InteractionVisualizer");
        boolean targetEnabled = target != null && target.isEnabled();
        SceneComposition composition = sceneComposition();
        String record = String.format(Locale.ROOT,
                "IV_COMPARE_STATUS collecting=%s scenario=%s expected=%d actual=%d player=%s " +
                        "targetEnabled=%s activeFurnaces=%d",
                collecting, scenario.isEmpty() ? "none" : scenario, requestedSceneSize, actual,
                observer.isEmpty() ? "none" : observer, targetEnabled, composition.activeFurnaces());
        getLogger().info(record);
        sender.sendMessage(record);
        return true;
    }

    @EventHandler
    public void onServerTickEnd(ServerTickEndEvent event) {
        if (!collecting) {
            return;
        }
        if (skipNextTickSample) {
            skipNextTickSample = false;
            boundaryTickSamplesDiscarded++;
            return;
        }
        if (tickSamples < tickDurations.length) {
            tickDurations[tickSamples++] = event.getTickDuration();
        } else {
            droppedTickSamples++;
        }
    }

    private Snapshot snapshot(long elapsedNanos) {
        double[] sorted = Arrays.copyOf(tickDurations, tickSamples);
        Arrays.sort(sorted);
        double mean = 0.0D;
        long over50 = 0L;
        for (double duration : sorted) {
            mean += duration;
            if (duration > 50.0D) {
                over50++;
            }
        }
        if (sorted.length > 0) {
            mean /= sorted.length;
        }
        Plugin target = Bukkit.getPluginManager().getPlugin("InteractionVisualizer");
        String targetVersion = target == null ? "missing" : target.getPluginMeta().getVersion();
        boolean targetEnabled = target != null && target.isEnabled();
        int actualSceneSize = sceneSize(scenario);
        boolean observerOnline = Bukkit.getPlayerExact(observer) != null;
        SceneComposition composition = sceneComposition();
        return new Snapshot(label, variant, scenario, requestedSceneSize, actualSceneSize,
                observer, observerOnline, targetVersion, targetEnabled,
                composition.furnaces(), composition.blastFurnaces(), composition.smokers(),
                composition.beehives(), composition.beeNests(), composition.activeFurnaces(),
                requestedFlags, effectiveFlags,
                elapsedNanos, tickSamples, boundaryTickSamplesDiscarded, droppedTickSamples,
                percentile(sorted, 0.50D), percentile(sorted, 0.95D), percentile(sorted, 0.99D),
                percentile(sorted, 0.999D), sorted.length == 0 ? 0.0D : sorted[sorted.length - 1],
                mean, over50);
    }

    private void createDroppedItems(World world, int count) {
        int columns = (int) Math.ceil(Math.sqrt(count));
        double spacing = 1.25D;
        double offset = (columns - 1) * spacing / 2.0D;
        for (int index = 0; index < count; index++) {
            double x = (index % columns) * spacing - offset + 0.5D;
            double z = (index / columns) * spacing - offset + 0.5D;
            world.getChunkAt(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4).load();
            ItemStack stack = new ItemStack(Material.STONE, index % 64 + 1);
            int itemId = index;
            stack.editMeta(meta -> meta.customName(Component.text("iv-compare-" + itemId)));
            Item item = world.dropItem(new Location(world, x, 80.0D, z), stack, entity -> {
                entity.addScoreboardTag(ITEM_TAG);
                entity.setGravity(false);
                entity.setVelocity(new Vector());
                entity.setPickupDelay(6_000);
            });
            item.setTicksLived(1);
        }
    }

    private void createActiveBlocks(World world, int count) {
        int columns = (int) Math.ceil(Math.sqrt(count));
        int offset = (columns - 1) / 2;
        for (int index = 0; index < count; index++) {
            int x = index % columns - offset;
            int z = index / columns - offset;
            world.getChunkAt(x >> 4, z >> 4).load();
            Block block = world.getBlockAt(x, 80, z);
            Material material = BLOCK_PATTERN[index % BLOCK_PATTERN.length];
            block.setType(material, false);
            configureDirection(block);
            BlockState state = block.getState();
            if (state instanceof Furnace furnace) {
                configureFurnace(furnace, material, index);
                if (!state.update(true, false)) {
                    throw new IllegalStateException("failed to update furnace at " + x + ",80," + z);
                }
            }
            sceneBlocks.add(block);
        }
    }

    private static void refreshTileEntityTracking(Player player, Location center) {
        // The candidate watches a 3x3 chunk square. Move four chunks so the
        // temporary and scene-side watcher sets cannot overlap, then return to
        // force every block created through direct Bukkit writes to be scanned.
        Location refresh = center.clone().add(TILE_TRACKING_REFRESH_BLOCKS, 0.0D, 0.0D);
        refresh.getChunk().load();
        if (!player.teleport(refresh) || !player.teleport(center)) {
            throw new IllegalStateException("failed to refresh tile-entity tracking");
        }
    }

    private static void configureDirection(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Directional directional
                && directional.getFaces().contains(org.bukkit.block.BlockFace.NORTH)) {
            directional.setFacing(org.bukkit.block.BlockFace.NORTH);
            block.setBlockData(data, false);
        }
        if (data instanceof org.bukkit.block.data.type.Beehive beehive) {
            beehive.setHoneyLevel(Math.floorMod(block.getX() + block.getZ(),
                    beehive.getMaximumHoneyLevel() + 1));
            block.setBlockData(data, false);
        }
    }

    private static void configureFurnace(Furnace furnace, Material material, int ordinal) {
        FurnaceInventory inventory = furnace.getSnapshotInventory();
        inventory.clear();
        Material input = material == Material.SMOKER ? Material.BEEF : Material.RAW_IRON;
        inventory.setSmelting(new ItemStack(input, 64));
        inventory.setFuel(new ItemStack(Material.COAL_BLOCK, 64));
        furnace.setBurnTime((short) 0);
        furnace.setCookTime((short) (ordinal % 20));
        furnace.setCookSpeedMultiplier(0.5D);
    }

    private void clearScene() {
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (item.getScoreboardTags().contains(ITEM_TAG)) {
                    item.remove();
                }
            }
        }
        for (Block block : sceneBlocks) {
            if (Arrays.asList(BLOCK_PATTERN).contains(block.getType())) {
                block.setType(Material.AIR, false);
            }
        }
        sceneBlocks.clear();
        scenario = "";
        requestedSceneSize = 0;
        observer = "";
    }

    private int sceneSize(String requestedScenario) {
        if (requestedScenario.equals("dropped-items")) {
            int total = 0;
            for (World world : Bukkit.getWorlds()) {
                for (Item item : world.getEntitiesByClass(Item.class)) {
                    if (item.getScoreboardTags().contains(ITEM_TAG)) {
                        total++;
                    }
                }
            }
            return total;
        }
        if (requestedScenario.equals("block-active")) {
            int total = 0;
            for (Block block : sceneBlocks) {
                if (Arrays.asList(BLOCK_PATTERN).contains(block.getType())) {
                    total++;
                }
            }
            return total;
        }
        return 0;
    }

    private SceneComposition sceneComposition() {
        int furnaces = 0;
        int blastFurnaces = 0;
        int smokers = 0;
        int beehives = 0;
        int beeNests = 0;
        int activeFurnaces = 0;
        for (Block block : sceneBlocks) {
            switch (block.getType()) {
                case FURNACE -> furnaces++;
                case BLAST_FURNACE -> blastFurnaces++;
                case SMOKER -> smokers++;
                case BEEHIVE -> beehives++;
                case BEE_NEST -> beeNests++;
                default -> {
                }
            }
            BlockState state = block.getState();
            if (state instanceof Furnace furnace && furnace.getBurnTime() > 0) {
                ItemStack smelting = furnace.getSnapshotInventory().getSmelting();
                if (smelting != null && !smelting.getType().isAir()) {
                    activeFurnaces++;
                }
            }
        }
        return new SceneComposition(
                furnaces, blastFurnaces, smokers, beehives, beeNests, activeFurnaces);
    }

    static double percentile(double[] sorted, double quantile) {
        if (sorted.length == 0) {
            return 0.0D;
        }
        int index = (int) Math.ceil(quantile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static String sanitize(String value) {
        String sanitized = value == null ? "" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (sanitized.isBlank()) {
            return "unnamed";
        }
        return sanitized.substring(0, Math.min(64, sanitized.length()));
    }

    static RequestedFlags readRequestedFlags(Plugin target) {
        if (!(target instanceof JavaPlugin javaPlugin)) {
            throw new IllegalStateException("InteractionVisualizer target is not a JavaPlugin");
        }
        return new RequestedFlags(
                javaPlugin.getConfig().getBoolean(
                        "Settings.Performance.VirtualItems.PacketOnlyStatic"),
                javaPlugin.getConfig().getBoolean(
                        "Settings.Performance.BlockUpdates.EventDriven"));
    }

    static EffectiveFlags inspectEffectiveFlags(Class<?> targetClass) {
        return new EffectiveFlags(
                inspectEffectiveFlag(targetClass, "packetOnlyStaticVirtualItems"),
                inspectEffectiveFlag(targetClass, "eventDrivenBlockUpdates"));
    }

    static EffectiveFlag inspectEffectiveFlag(Class<?> targetClass, String fieldName) {
        final Field field;
        try {
            field = targetClass.getField(fieldName);
        } catch (NoSuchFieldException ignored) {
            return EffectiveFlag.unsupportedLegacy(fieldName);
        }
        if (field.getType() != boolean.class || !Modifier.isPublic(field.getModifiers())
                || !Modifier.isStatic(field.getModifiers())) {
            throw new IllegalStateException(
                    "Optimization flag is not a public static boolean: " + fieldName);
        }
        try {
            return EffectiveFlag.runtimeField(fieldName, field.getBoolean(null));
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Cannot inspect effective optimization flag: " + fieldName, exception);
        }
    }

    static void assertOptimizationProfile(
            String variant, RequestedFlags requested, EffectiveFlags effective) {
        if (requested.packetOnlyStatic() != requested.eventDrivenBlockUpdates()) {
            throw new IllegalStateException(
                    "Controlled runtime profile must request both optimization flags together");
        }
        if (!requested.packetOnlyStatic()) {
            return;
        }
        if (variant.equals("A")) {
            if (!effective.packetOnlyStatic().unsupportedLegacy()
                    || !effective.eventDrivenBlockUpdates().unsupportedLegacy()) {
                throw new IllegalStateException(
                        "Official upstream unexpectedly implements candidate optimization flags");
            }
            return;
        }
        if (!effective.packetOnlyStatic().enabled()
                || !effective.eventDrivenBlockUpdates().enabled()) {
            throw new IllegalStateException(
                    "Optimized candidate did not activate PacketOnlyStatic/EventDriven at runtime");
        }
    }

    private record SceneComposition(
            int furnaces,
            int blastFurnaces,
            int smokers,
            int beehives,
            int beeNests,
            int activeFurnaces) {
    }

    record RequestedFlags(boolean packetOnlyStatic, boolean eventDrivenBlockUpdates) {

        static RequestedFlags disabled() {
            return new RequestedFlags(false, false);
        }

        String json() {
            return String.format(Locale.ROOT,
                    "{\"packetOnlyStatic\":%b,\"eventDrivenBlockUpdates\":%b}",
                    packetOnlyStatic, eventDrivenBlockUpdates);
        }
    }

    record EffectiveFlag(String status, Boolean value, String fieldName) {

        static EffectiveFlag unsupportedLegacy(String fieldName) {
            return new EffectiveFlag("unsupported-legacy", null, fieldName);
        }

        static EffectiveFlag runtimeField(String fieldName, boolean value) {
            return new EffectiveFlag("runtime-field", value, fieldName);
        }

        boolean unsupportedLegacy() {
            return status.equals("unsupported-legacy") && value == null;
        }

        boolean enabled() {
            return status.equals("runtime-field") && Boolean.TRUE.equals(value);
        }

        String json() {
            String encodedValue = value == null ? "null" : value.toString();
            return String.format(Locale.ROOT,
                    "{\"status\":\"%s\",\"value\":%s,\"field\":\"%s\"}",
                    status, encodedValue, fieldName);
        }
    }

    record EffectiveFlags(
            EffectiveFlag packetOnlyStatic,
            EffectiveFlag eventDrivenBlockUpdates) {

        static EffectiveFlags unsupportedLegacy() {
            return new EffectiveFlags(
                    EffectiveFlag.unsupportedLegacy("packetOnlyStaticVirtualItems"),
                    EffectiveFlag.unsupportedLegacy("eventDrivenBlockUpdates"));
        }

        String json() {
            return String.format(Locale.ROOT,
                    "{\"packetOnlyStatic\":%s,\"eventDrivenBlockUpdates\":%s}",
                    packetOnlyStatic.json(), eventDrivenBlockUpdates.json());
        }
    }

    private record Snapshot(
            String label,
            String variant,
            String scenario,
            int expectedSceneSize,
            int actualSceneSize,
            String observer,
            boolean observerOnline,
            String targetVersion,
            boolean targetEnabled,
            int furnaceBlocks,
            int blastFurnaceBlocks,
            int smokerBlocks,
            int beehiveBlocks,
            int beeNestBlocks,
            int activeFurnaces,
            RequestedFlags requestedFlags,
            EffectiveFlags effectiveFlags,
            long elapsedNanos,
            int tickSamples,
            int boundaryTickSamplesDiscarded,
            long droppedTickSamples,
            double msptP50,
            double msptP95,
            double msptP99,
            double msptP999,
            double msptMax,
            double msptMean,
            long ticksOver50ms) {

        double seconds() {
            return elapsedNanos / 1_000_000_000.0D;
        }

        double observedTps() {
            return seconds() <= 0.0D
                    ? 0.0D
                    : (tickSamples + boundaryTickSamplesDiscarded + droppedTickSamples) / seconds();
        }

        String json() {
            return String.format(Locale.ROOT,
                    "{\"schemaVersion\":1,\"label\":\"%s\",\"variant\":\"%s\"," +
                            "\"scenario\":\"%s\",\"expectedSceneSize\":%d," +
                            "\"actualSceneSize\":%d,\"observer\":\"%s\"," +
                            "\"observerOnline\":%b,\"targetVersion\":\"%s\",\"targetEnabled\":%b," +
                            "\"furnaceBlocks\":%d,\"blastFurnaceBlocks\":%d," +
                            "\"smokerBlocks\":%d,\"beehiveBlocks\":%d," +
                            "\"beeNestBlocks\":%d,\"activeFurnaces\":%d," +
                            "\"requestedFlags\":%s,\"effectiveFlags\":%s," +
                            "\"seconds\":%.6f,\"tickSamples\":%d," +
                            "\"boundaryTickSamplesDiscarded\":%d,\"droppedTickSamples\":%d," +
                            "\"observedTps\":%.6f,\"msptP50\":%.6f,\"msptP95\":%.6f," +
                            "\"msptP99\":%.6f,\"msptP999\":%.6f,\"msptMax\":%.6f," +
                            "\"msptMean\":%.6f,\"ticksOver50ms\":%d}",
                    label, variant, scenario, expectedSceneSize, actualSceneSize, observer,
                    observerOnline, targetVersion, targetEnabled,
                    furnaceBlocks, blastFurnaceBlocks, smokerBlocks, beehiveBlocks,
                    beeNestBlocks, activeFurnaces, requestedFlags.json(), effectiveFlags.json(),
                    seconds(), tickSamples,
                    boundaryTickSamplesDiscarded, droppedTickSamples,
                    observedTps(), msptP50, msptP95, msptP99, msptP999, msptMax,
                    msptMean, ticksOver50ms);
        }
    }
}
