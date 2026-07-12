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

package com.loohp.interactionvisualizer.updater;

import com.google.gson.JsonObject;
import com.loohp.interactionvisualizer.InteractionVisualizer;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import com.loohp.interactionvisualizer.utils.HTTPRequestUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class Updater implements Listener {

    public static final String PLUGIN_NAME = "InteractionVisualizer";
    private static final long CACHE_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
    private static volatile UpdaterResponse cachedResponse;
    private static volatile long cachedAt;

    public static void sendUpdateMessage(CommandSender sender, String version, int spigotPluginId) {
        sendUpdateMessage(sender, version, spigotPluginId, false);
    }

    public static void sendUpdateMessage(CommandSender sender, String version, int spigotPluginId, boolean devbuild) {
        if (!version.equals("error")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                if (!devbuild) {
                    player.sendMessage(Component.text(
                            "[InteractionVisualizer] A new version is available on SpigotMC: " + version,
                            NamedTextColor.YELLOW));
                    Component url = Component.text(
                            "https://www.spigotmc.org/resources/" + spigotPluginId, NamedTextColor.GOLD);
                    url = url.hoverEvent(HoverEvent.showText(Component.text("Click me!").color(NamedTextColor.AQUA)));
                    url = url.clickEvent(ClickEvent.openUrl("https://www.spigotmc.org/resources/" + spigotPluginId));
                    InteractionVisualizer.sendMessage(player, url);
                } else {
                    sender.sendMessage(Component.text(
                            "[InteractionVisualizer] You are running the latest release!", NamedTextColor.GREEN));
                    Component url = Component.text(
                            "[InteractionVisualizer] However, a new Development Build is available if you want to try that!",
                            NamedTextColor.YELLOW);
                    url = url.hoverEvent(HoverEvent.showText(Component.text("Click me!").color(NamedTextColor.AQUA)));
                    url = url.clickEvent(ClickEvent.openUrl("https://ci.loohpjames.com/job/" + PLUGIN_NAME));
                    InteractionVisualizer.sendMessage(player, url);
                }
            } else {
                if (!devbuild) {
                    sender.sendMessage(Component.text(
                            "[InteractionVisualizer] A new version is available on SpigotMC: " + version,
                            NamedTextColor.YELLOW));
                    sender.sendMessage(Component.text(
                            "Download: https://www.spigotmc.org/resources/" + spigotPluginId, NamedTextColor.GOLD));
                } else {
                    sender.sendMessage(Component.text(
                            "[InteractionVisualizer] You are running the latest release!", NamedTextColor.GREEN));
                    sender.sendMessage(Component.text(
                            "[InteractionVisualizer] However, a new Development Build is available if you want to try that!",
                            NamedTextColor.YELLOW));
                }
            }
        }
    }

    public static synchronized UpdaterResponse checkUpdate() {
        long now = System.nanoTime();
        if (cachedResponse != null && now - cachedAt < CACHE_NANOS) {
            return cachedResponse;
        }
        try {
            String localPluginVersion = InteractionVisualizer.plugin.getPluginMeta().getVersion();
            JsonObject root = HTTPRequestUtils.getJSONResponse("https://api.loohpjames.com/spigot/data");
            if (root == null) {
                throw new IllegalStateException("Update service returned no JSON");
            }
            JsonObject response = root.getAsJsonObject(PLUGIN_NAME);
            JsonObject latestVersion = response.getAsJsonObject("latestversion");
            String spigotPluginVersion = latestVersion.get("release").getAsString();
            String devBuildVersion = latestVersion.get("devbuild").getAsString();
            int spigotPluginId = response.getAsJsonObject("spigotmc").get("pluginid").getAsInt();
            int posOfThirdDot = localPluginVersion.indexOf(".", localPluginVersion.indexOf(".", localPluginVersion.indexOf(".") + 1) + 1);
            Version currentDevBuild = new Version(localPluginVersion);
            Version currentRelease = new Version(localPluginVersion.substring(0, posOfThirdDot >= 0 ? posOfThirdDot : localPluginVersion.length()));
            Version spigotmc = new Version(spigotPluginVersion);
            Version devBuild = new Version(devBuildVersion);
            if (currentRelease.compareTo(spigotmc) < 0) {
                return cache(new UpdaterResponse(spigotPluginVersion, spigotPluginId, currentDevBuild.compareTo(devBuild) >= 0));
            } else {
                return cache(new UpdaterResponse("latest", spigotPluginId, currentDevBuild.compareTo(devBuild) >= 0));
            }
        } catch (Exception e) {
            InteractionVisualizer.plugin.getLogger().warning(
                    "Failed to check api.loohpjames.com for updates; disable Options.Updater to skip this check.");
        }
        return cache(new UpdaterResponse("error", -1, false));
    }

    private static UpdaterResponse cache(UpdaterResponse response) {
        cachedResponse = response;
        cachedAt = System.nanoTime();
        return response;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!InteractionVisualizer.updaterEnabled || !player.hasPermission("interactionvisualizer.update")) {
            return;
        }
        InteractionVisualizer.asyncExecutorManager.runTaskLaterAsynchronously(() -> {
            UpdaterResponse version = Updater.checkUpdate();
            if (!version.getResult().equals("latest")) {
                Scheduler.runTask(InteractionVisualizer.plugin, () -> {
                    if (player.isOnline()) {
                        Updater.sendUpdateMessage(player, version.getResult(), version.getSpigotPluginId());
                    }
                });
            }
        }, 100);
    }

    public static class UpdaterResponse {

        private final String result;
        private final int spigotPluginId;
        private final boolean devBuildIsLatest;

        public UpdaterResponse(String result, int spigotPluginId, boolean devBuildIsLatest) {
            this.result = result;
            this.spigotPluginId = spigotPluginId;
            this.devBuildIsLatest = devBuildIsLatest;
        }

        public String getResult() {
            return result;
        }

        public int getSpigotPluginId() {
            return spigotPluginId;
        }

        public boolean isDevBuildLatest() {
            return devBuildIsLatest;
        }

    }

}
