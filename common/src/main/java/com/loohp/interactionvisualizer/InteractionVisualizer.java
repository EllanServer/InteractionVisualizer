/*
 * This file is part of InteractionVisualizer.
 *
 * Copyright (C) 2025. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2025. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.loohp.interactionvisualizer;

import com.loohp.interactionvisualizer.api.events.InteractionVisualizerReloadEvent;
import com.loohp.interactionvisualizer.config.Config;
import com.loohp.interactionvisualizer.database.Database;
import com.loohp.interactionvisualizer.debug.PerformanceBlockScene;
import com.loohp.interactionvisualizer.debug.PerformanceScene;
import com.loohp.interactionvisualizer.integration.CustomContentManager;
import com.loohp.interactionvisualizer.managers.AsyncExecutorManager;
import com.loohp.interactionvisualizer.managers.LangManager;
import com.loohp.interactionvisualizer.managers.LightManager;
import com.loohp.interactionvisualizer.managers.MaterialManager;
import com.loohp.interactionvisualizer.managers.MusicManager;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.managers.PreferenceManager;
import com.loohp.interactionvisualizer.managers.PerformanceMetrics;
import com.loohp.interactionvisualizer.managers.TaskManager;
import com.loohp.interactionvisualizer.managers.TileEntityManager;
import com.loohp.interactionvisualizer.metrics.Charts;
import com.loohp.interactionvisualizer.metrics.Metrics;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.objectholders.ILightManager;
import com.loohp.interactionvisualizer.placeholderAPI.Placeholders;
import com.loohp.interactionvisualizer.updater.Updater;
import com.loohp.interactionvisualizer.updater.Updater.UpdaterResponse;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import com.loohp.interactionvisualizer.config.SparrowConfiguration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class InteractionVisualizer extends JavaPlugin {

    public static final int BSTATS_PLUGIN_ID = 7024;
    public static final String CONFIG_ID = "config";
    public static final Set<String> SUPPORTED_MINECRAFT_VERSIONS = Set.of("26.1.2", "26.2");

    public static InteractionVisualizer plugin = null;

    public static Boolean lightapi = false;
    public static Boolean openinv = false;

    public static Set<String> exemptBlocks = new HashSet<>();
    public static Set<String> disabledWorlds = new HashSet<>();

    public static boolean itemStandEnabled = true;
    public static boolean itemDropEnabled = true;
    public static boolean hologramsEnabled = true;
    public static Set<EntryKey> itemStandDisabled = new HashSet<>();
    public static Set<EntryKey> itemDropDisabled = new HashSet<>();
    public static Set<EntryKey> hologramsDisabled = new HashSet<>();

    public static Double playerPickupYOffset = 0.0;

    public static Integer tileEntityCheckingRange = 1;
    public static double ignoreWalkSquared = 0.0;
    public static double ignoreFlySquared = 0.0;
    public static double ignoreGlideSquared = 0.0;

    public static Boolean handMovementEnabled = true;

    public static Integer lightUpdatePeriod = 10;

    public static boolean updaterEnabled = true;

    public static Map<World, Integer> playerTrackingRange = new ConcurrentHashMap<>();
    public static boolean hideIfObstructed = false;

    public static boolean defaultDisabledAll = false;
    /** A/B switch: virtual item remains authoritative while an invisible tracker stays stationary. */
    public static boolean staticVirtualItemAnchorsDuringAnimation = false;
    /** A/B switch: eligible stationary virtual items are tracked and rendered entirely by packets. */
    public static boolean packetOnlyStaticVirtualItems = false;
    /** A/B switch: smooths visibility recovery bursts; hides are always immediate. */
    public static boolean visibilityRateLimiting = false;
    public static int visibilityRateLimitBucketSize = 128;
    public static int visibilityRateLimitRestorePerTick = 32;
    /** A/B switch: coalesces block changes and updates tracked blocks from fixed-budget loops. */
    public static boolean eventDrivenBlockUpdates = false;
    public static int blockUpdateMaxDirtyPerTick = 64;

    private boolean blockUpdateModeInitialized;

    public static ILightManager lightManager;
    public static PreferenceManager preferenceManager;
    public static AsyncExecutorManager asyncExecutorManager;

    private static void hookMessage(String pluginName) {
        Bukkit.getConsoleSender().sendMessage(Component.text(
                "[InteractionVisualizer] Hooked into " + pluginName + "!", NamedTextColor.LIGHT_PURPLE));
    }

    public static void sendMessage(CommandSender sender, Component component) {
        sender.sendMessage(component);
    }

    public static boolean isPluginEnabled(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public void onEnable() {
        plugin = this;

        String minecraftVersion = getServer().getMinecraftVersion();
        if (!SUPPORTED_MINECRAFT_VERSIONS.contains(minecraftVersion)) {
            getComponentLogger().error("InteractionVisualizer supports Paper 26.1.2 and 26.2 only; found {}", minecraftVersion);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);

        int workers = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        ThreadFactory factory = Thread.ofPlatform().daemon(true).name("InteractionVisualizer-Worker-", 0).factory();
        ExecutorService threadPool = new ThreadPoolExecutor(
                workers, workers, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2048), factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
        asyncExecutorManager = new AsyncExecutorManager(threadPool);

        if (isPluginEnabled("LightAPI")) {
            try {
                Class.forName("ru.beykerykt.lightapi.utils.Debug");
                hookMessage("LightAPI");
                lightapi = true;
                lightManager = new LightManager(this);
            } catch (ClassNotFoundException ignored) {
            }
        }
        if (!lightapi) {
            lightManager = ILightManager.DUMMY_INSTANCE;
        }
        if (isPluginEnabled("OpenInv")) {
            hookMessage("OpenInv");
            openinv = true;
        }

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        try {
            Config.loadConfig(CONFIG_ID, new File(getDataFolder(), "config.yml"), getClass().getClassLoader().getResourceAsStream("config.yml"), getClass().getClassLoader().getResourceAsStream("config.yml"), true);
        } catch (IOException e) {
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loadConfig();

        if (CustomContentManager.initialize(this).contains("craftengine")) {
            hookMessage("CraftEngine");
        }

        if (getConfiguration().getBoolean("Options.DownloadLanguageFiles")) {
            asyncExecutorManager.runTaskAsynchronously(LangManager::generate);
        }

        MusicManager.setup();
        Database.setup();
        preferenceManager = new PreferenceManager(this);
        TaskManager.setup();
        TileEntityManager._init_();
        DisplayManager.run();

        MaterialManager.setup();

        getCommand("interactionvisualizer").setExecutor(new Commands());
        PerformanceMetrics.register(this);

        TaskManager.run();

        Charts.registerCharts(metrics);

        if (isPluginEnabled("PlaceholderAPI")) {
            new Placeholders().register();
        }

        exemptBlocks.add("CRAFTING_TABLE");
        exemptBlocks.add("CRAFTER");
        exemptBlocks.add("LOOM");
        exemptBlocks.add("SMITHING_TABLE");
        exemptBlocks.add("SPAWNER");
        exemptBlocks.add("BEACON");
        
        getServer().getConsoleSender().sendMessage(Component.text(
                "[InteractionVisualizer] Enabled for Paper " + minecraftVersion + "!", NamedTextColor.GREEN));

        Scheduler.runTask(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                DisplayManager.playerStatus.put(player, ConcurrentHashMap.newKeySet());
            }
        });

        InteractionVisualizer.asyncExecutorManager.runTaskLaterAsynchronously(() -> {
            if (updaterEnabled) {
                UpdaterResponse version = Updater.checkUpdate();
                if (!version.getResult().equals("latest")) {
                    Scheduler.runTask(this, () -> {
                        Updater.sendUpdateMessage(Bukkit.getConsoleSender(), version.getResult(), version.getSpigotPluginId());
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            if (player.hasPermission("interactionvisualizer.update")) {
                                Updater.sendUpdateMessage(player, version.getResult(), version.getSpigotPluginId());
                            }
                        }
                    });
                }
            }
        }, 100);
    }

    @Override
    public void onDisable() {
        shutdownPerformanceScenes();
        CustomContentManager.shutdown();
        if (preferenceManager != null) {
            preferenceManager.close();
        }
        DisplayManager.shutdown();
        if (asyncExecutorManager != null) {
            asyncExecutorManager.close();
        }
        getServer().getConsoleSender().sendMessage(Component.text(
                "[InteractionVisualizer] Disabled; all display entities removed.", NamedTextColor.RED));
    }

    private void shutdownPerformanceScenes() {
        try {
            PerformanceBlockScene.ShutdownReport report = PerformanceBlockScene.shutdown();
            if (report.unresolvedCount() != 0) {
                getLogger().severe("Performance block-scene cleanup left blocks unresolved during disable: "
                        + report.summary() + "; unloaded or externally modified blocks were left untouched");
            }
        } catch (Throwable throwable) {
            logPerformanceCleanupFailure("Performance block-scene cleanup failed; core disable will continue",
                    throwable);
        }
        try {
            PerformanceScene.shutdown();
        } catch (Throwable throwable) {
            logPerformanceCleanupFailure("Performance display-scene cleanup failed; core disable will continue",
                    throwable);
        }
    }

    private void logPerformanceCleanupFailure(String message, Throwable throwable) {
        try {
            getLogger().log(Level.SEVERE, message, throwable);
        } catch (Throwable ignored) {
            // Diagnostic logging must never prevent the plugin's core shutdown.
        }
    }

    public SparrowConfiguration getConfiguration() {
        return Config.getConfig(CONFIG_ID).getConfiguration();
    }

    public void loadConfig() {
        Config config = Config.getConfig(CONFIG_ID);
        config.reload();

        itemStandEnabled = getConfiguration().getBoolean("Modules.ItemStand.Enabled");
        itemDropEnabled = getConfiguration().getBoolean("Modules.ItemDrop.Enabled");
        hologramsEnabled = getConfiguration().getBoolean("Modules.Hologram.Enabled");

        itemStandDisabled = getConfiguration().getStringList("Modules.ItemStand.OverridingDisabled").stream().map(each -> new EntryKey(each)).collect(Collectors.toSet());
        itemDropDisabled = getConfiguration().getStringList("Modules.ItemDrop.OverridingDisabled").stream().map(each -> new EntryKey(each)).collect(Collectors.toSet());
        hologramsDisabled = getConfiguration().getStringList("Modules.Hologram.OverridingDisabled").stream().map(each -> new EntryKey(each)).collect(Collectors.toSet());

        playerPickupYOffset = getConfiguration().getDouble("Settings.PickupAnimationPlayerYOffset");

        tileEntityCheckingRange = getConfiguration().getInt("TileEntityUpdate.CheckingRange");
        ignoreWalkSquared = getConfiguration().getDouble("TileEntityUpdate.IgnoreMovementSpeed.Normal");
        ignoreWalkSquared *= ignoreWalkSquared;
        ignoreFlySquared = getConfiguration().getDouble("TileEntityUpdate.IgnoreMovementSpeed.Flying");
        ignoreFlySquared *= ignoreFlySquared;
        ignoreGlideSquared = getConfiguration().getDouble("TileEntityUpdate.IgnoreMovementSpeed.Gliding");
        ignoreGlideSquared *= ignoreGlideSquared;

        handMovementEnabled = getConfiguration().getBoolean("Settings.UseHandSwingAnimation");

        disabledWorlds = new HashSet<>(getConfiguration().getStringList("Settings.DisabledWorlds"));
        hideIfObstructed = getConfiguration().getBoolean("Settings.HideIfViewObstructed");

        lightUpdatePeriod = getConfiguration().getInt("LightUpdate.Period");

        updaterEnabled = getConfiguration().getBoolean("Options.Updater");

        playerTrackingRange.clear();
        for (World world : getServer().getWorlds()) {
            playerTrackingRange.put(world, Math.max(32, world.getSendViewDistance() * 16));
        }

        defaultDisabledAll = getConfiguration().getBoolean("Settings.DefaultDisableAll");
        staticVirtualItemAnchorsDuringAnimation = getConfiguration().getBoolean(
                "Settings.Performance.VirtualItems.StaticAnchorDuringAnimation");
        packetOnlyStaticVirtualItems = getConfiguration().getBoolean(
                "Settings.Performance.VirtualItems.PacketOnlyStatic");
        visibilityRateLimiting = getConfiguration().getBoolean(
                "Settings.Performance.VisibilityRateLimit.Enabled");
        visibilityRateLimitBucketSize = Math.max(1, getConfiguration().getInt(
                "Settings.Performance.VisibilityRateLimit.BucketSize"));
        visibilityRateLimitRestorePerTick = Math.max(1, getConfiguration().getInt(
                "Settings.Performance.VisibilityRateLimit.RestorePerTick"));
        boolean configuredEventDrivenBlockUpdates = getConfiguration().getBoolean(
                "Settings.Performance.BlockUpdates.EventDriven");
        if (!blockUpdateModeInitialized) {
            eventDrivenBlockUpdates = configuredEventDrivenBlockUpdates;
        } else if (eventDrivenBlockUpdates != configuredEventDrivenBlockUpdates) {
            getLogger().warning("Settings.Performance.BlockUpdates.EventDriven is applied only at startup; "
                    + "restart the server to change the active block update mode.");
        }
        blockUpdateMaxDirtyPerTick = Math.max(1, getConfiguration().getInt(
                "Settings.Performance.BlockUpdates.MaxDirtyPerTick"));
        blockUpdateModeInitialized = true;

        getServer().getPluginManager().callEvent(new InteractionVisualizerReloadEvent());
    }

}
