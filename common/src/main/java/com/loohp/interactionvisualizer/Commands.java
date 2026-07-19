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

import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI.Modules;
import com.loohp.interactionvisualizer.debug.PerformanceBlockScene;
import com.loohp.interactionvisualizer.managers.MaterialManager;
import com.loohp.interactionvisualizer.managers.MusicManager;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.managers.PerformanceMetrics;
import com.loohp.interactionvisualizer.debug.PerformanceScene;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.updater.Updater;
import com.loohp.interactionvisualizer.updater.Updater.UpdaterResponse;
import com.loohp.interactionvisualizer.utils.ChatColorUtils;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class Commands implements CommandExecutor, TabCompleter {

    private static final InteractionVisualizer plugin = InteractionVisualizer.plugin;

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!label.equalsIgnoreCase("interactionvisualizer") && !label.equalsIgnoreCase("iv")) {
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(Component.text("[InteractionVisualizer] InteractionVisualizer written by LOOHP!", NamedTextColor.AQUA));
            sender.sendMessage(Component.text("[InteractionVisualizer] Running InteractionVisualizer version: " + plugin.getPluginMeta().getVersion(), NamedTextColor.GOLD));
            return true;
        }

        if (args[0].equalsIgnoreCase("perf")) {
            return handlePerformanceCommand(sender, args);
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (sender.hasPermission("interactionvisualizer.reload")) {
                plugin.loadConfig();
                MusicManager.reloadConfig();
                MaterialManager.reloadConfig();
                DisplayManager.update();
                sender.sendMessage(ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.Reload")));
            } else {
                sender.sendMessage(ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.NoPermission")));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("refresh")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                if (player.hasPermission("interactionvisualizer.refresh")) {
                    Scheduler.runTask(InteractionVisualizer.plugin, () -> DisplayManager.reset(player));
                } else {
                    sender.sendMessage(ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.NoPermission")));
                }
            } else {
                sender.sendMessage(ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.Toggle.Console")));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("update")) {
            if (sender.hasPermission("interactionvisualizer.update")) {
                sender.sendMessage(Component.text("[InteractionVisualizer] InteractionVisualizer written by LOOHP!", NamedTextColor.AQUA));
                sender.sendMessage(Component.text("[InteractionVisualizer] You are running InteractionVisualizer version: " + plugin.getPluginMeta().getVersion(), NamedTextColor.GOLD));
                InteractionVisualizer.asyncExecutorManager.runTaskAsynchronously(() -> {
                    UpdaterResponse version = Updater.checkUpdate();
                    Scheduler.runTask(plugin, () -> {
                        if (version.getResult().equals("latest")) {
                            if (version.isDevBuildLatest()) {
                                sender.sendMessage(Component.text("[InteractionVisualizer] You are running the latest version!", NamedTextColor.GREEN));
                            } else {
                                Updater.sendUpdateMessage(sender, version.getResult(), version.getSpigotPluginId(), true);
                            }
                        } else {
                            Updater.sendUpdateMessage(sender, version.getResult(), version.getSpigotPluginId());
                        }
                    });
                });
            } else {
                sender.sendMessage(ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.NoPermission")));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("toggle")) {
            if (sender.hasPermission("interactionvisualizer.toggle")) {
                if ((args.length == 4 && (args[3].equalsIgnoreCase("true") || args[3].equalsIgnoreCase("false"))) || args.length == 3) {
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.Toggle.Console")));
                        return true;
                    }
                    Player player = (Player) sender;
                    InteractionVisualizer.asyncExecutorManager.runTaskSynchronously(() -> {
                        EntryKey[] entries;
                        String verboseEntry = null;
                        if (args[2].equalsIgnoreCase("all")) {
                            entries = InteractionVisualizer.preferenceManager.getRegisteredEntries().toArray(new EntryKey[0]);
                            verboseEntry = ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.Toggle.All"));
                        } else {
                            entries = new EntryKey[] {new EntryKey(args[2])};
                        }
                        if (args.length == 4) {
                            boolean value = args[3].equalsIgnoreCase("true");
                            switch (args[1].toLowerCase()) {
                                case "itemstand":
                                    Toggle.toggle(sender, player, Modules.ITEMSTAND, value, true, verboseEntry, entries);
                                    break;
                                case "itemdrop":
                                    Toggle.toggle(sender, player, Modules.ITEMDROP, value, true, verboseEntry, entries);
                                    break;
                                case "hologram":
                                    Toggle.toggle(sender, player, Modules.HOLOGRAM, value, true, verboseEntry, entries);
                                    break;
                                case "all":
                                    for (Modules modules : Modules.values()) {
                                        Toggle.toggle(sender, player, modules, value, true, verboseEntry, entries);
                                    }
                                    break;
                                default:
                                    sender.sendMessage(ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.Toggle.Modes")));
                            }
                        } else {
                            switch (args[1].toLowerCase()) {
                                case "itemstand":
                                    Toggle.toggle(sender, player, Modules.ITEMSTAND, true, verboseEntry, entries);
                                    break;
                                case "itemdrop":
                                    Toggle.toggle(sender, player, Modules.ITEMDROP, true, verboseEntry, entries);
                                    break;
                                case "hologram":
                                    Toggle.toggle(sender, player, Modules.HOLOGRAM, true, verboseEntry, entries);
                                    break;
                                case "all":
                                    for (Modules modules : Modules.values()) {
                                        Toggle.toggle(sender, player, modules, true, verboseEntry, entries);
                                    }
                                    break;
                                default:
                                    sender.sendMessage(ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.Toggle.Modes")));
                            }
                        }
                    });
                    return true;
                } else if (args.length == 5 || args.length == 4) {
                    Player player = Bukkit.getPlayer(args[args.length == 4 ? 3 : 4]);
                    if (sender instanceof Player) {
                        if (player != null) {
                            if (!player.equals(sender)) {
                                if (!sender.hasPermission("interactionvisualizer.toggle.others")) {
                                    sender.sendMessage(ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.NoPermission")));
                                    return true;
                                }
                            }
                        }
                    }
                    if (player == null) {
                        sender.sendMessage(ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.Toggle.PlayerNotFound")));
                        return true;
                    }
                    InteractionVisualizer.asyncExecutorManager.runTaskSynchronously(() -> {
                        EntryKey[] entries;
                        String verboseEntry = null;
                        if (args[2].equalsIgnoreCase("all")) {
                            entries = InteractionVisualizer.preferenceManager.getRegisteredEntries().toArray(new EntryKey[0]);
                            verboseEntry = ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.Toggle.All"));
                        } else {
                            entries = new EntryKey[] {new EntryKey(args[2])};
                        }
                        if (args.length == 5) {
                            boolean value = args[3].equalsIgnoreCase("true");
                            switch (args[1].toLowerCase()) {
                                case "itemstand":
                                    Toggle.toggle(sender, player, Modules.ITEMSTAND, value, true, verboseEntry, entries);
                                    break;
                                case "itemdrop":
                                    Toggle.toggle(sender, player, Modules.ITEMDROP, value, true, verboseEntry, entries);
                                    break;
                                case "hologram":
                                    Toggle.toggle(sender, player, Modules.HOLOGRAM, value, true, verboseEntry, entries);
                                    break;
                                case "all":
                                    for (Modules modules : Modules.values()) {
                                        Toggle.toggle(sender, player, modules, value, true, verboseEntry, entries);
                                    }
                                    break;
                                default:
                                    sender.sendMessage(ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.Toggle.Modes")));
                            }
                        } else {
                            switch (args[1].toLowerCase()) {
                                case "itemstand":
                                    Toggle.toggle(sender, player, Modules.ITEMSTAND, true, verboseEntry, entries);
                                    break;
                                case "itemdrop":
                                    Toggle.toggle(sender, player, Modules.ITEMDROP, true, verboseEntry, entries);
                                    break;
                                case "hologram":
                                    Toggle.toggle(sender, player, Modules.HOLOGRAM, true, verboseEntry, entries);
                                    break;
                                case "all":
                                    for (Modules modules : Modules.values()) {
                                        Toggle.toggle(sender, player, modules, true, verboseEntry, entries);
                                    }
                                    break;
                                default:
                                    sender.sendMessage(ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.Toggle.Modes")));
                            }
                        }
                    });
                    return true;
                } else {
                    sender.sendMessage(ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.Toggle.Usage")));
                }
                return true;
            }
            sender.sendMessage(ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.NoPermission")));
            return true;
        }

        if (args[0].equalsIgnoreCase("ethereal")) {
            Component text = Component.text("She is Imaginary~~");
            text = text.color(NamedTextColor.YELLOW);
            Component bone = LegacyComponentSerializer.legacySection().deserialize("\u00a7eNana's Bone\n\u00a77Lost \u00a76In-\u00a7dMaginary~~");
            text = text.hoverEvent(HoverEvent.showText(bone)).clickEvent(ClickEvent.openUrl("https://www.instagram.com/narliar/"));
            InteractionVisualizer.sendMessage(sender, text);
            return true;
        }

        String unknownCommand = plugin.getConfiguration().getString("Messages.UnknownCommand");
        sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                unknownCommand == null ? "&cUnknown command. Type \"/help\" for help." : unknownCommand));
        return true;
    }

    private boolean handlePerformanceCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("interactionvisualizer.performance")) {
            sender.sendMessage(ChatColorUtils.translateAlternateColorCodes('&', plugin.getConfiguration().getString("Messages.NoPermission")));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /iv perf <start|stop|status|scene|clear|blockscene>"));
            return true;
        }
        switch (args[1].toLowerCase()) {
            case "start" -> {
                String label = args.length >= 3 ? args[2] : "unnamed";
                if (PerformanceMetrics.start(label)) {
                    sender.sendMessage(Component.text("[InteractionVisualizer] Performance sampling started: " + label));
                } else {
                    sender.sendMessage(Component.text("[InteractionVisualizer] Performance sampling is already active."));
                }
            }
            case "stop" -> {
                PerformanceMetrics.Snapshot snapshot = PerformanceMetrics.stop();
                if (snapshot == null) {
                    sender.sendMessage(Component.text("[InteractionVisualizer] Performance sampling is not active."));
                } else {
                    sender.sendMessage(Component.text("[InteractionVisualizer] " + snapshot.summary()));
                }
            }
            case "status" -> {
                PerformanceMetrics.Snapshot snapshot = PerformanceMetrics.snapshot();
                if (snapshot == null) {
                    sender.sendMessage(Component.text("[InteractionVisualizer] Performance sampling is not active."));
                } else {
                    sender.sendMessage(Component.text("[InteractionVisualizer] " + snapshot.summary()));
                }
            }
            case "scene" -> {
                if (args.length < 4) {
                    sender.sendMessage(Component.text(
                            "Usage: /iv perf scene <static|motion|itemdisplay|textdisplay|dropped> " +
                                    "<count> [lifetimeTicks] [player] [nearbyCount]"));
                    return true;
                }
                boolean moving = args[2].equalsIgnoreCase("motion");
                boolean staticItem = args[2].equalsIgnoreCase("static");
                boolean itemDisplay = args[2].equalsIgnoreCase("itemdisplay");
                boolean textDisplay = args[2].equalsIgnoreCase("textdisplay");
                boolean dropped = args[2].equalsIgnoreCase("dropped");
                if (!moving && !staticItem && !itemDisplay && !textDisplay && !dropped) {
                    sender.sendMessage(Component.text(
                            "[InteractionVisualizer] Scene type must be static, motion, itemdisplay, textdisplay, or dropped."));
                    return true;
                }
                if (args.length > 7 || args.length == 7 && !dropped) {
                    sender.sendMessage(Component.text(
                            "[InteractionVisualizer] nearbyCount is only valid for dropped scenes."));
                    return true;
                }
                long defaultLifetime = moving ? 80L : 200L;
                long lifetime = defaultLifetime;
                String requestedPlayer = args.length >= 6 ? args[5] : null;
                if (args.length >= 5) {
                    try {
                        lifetime = Long.parseLong(args[4]);
                    } catch (NumberFormatException ignored) {
                        if (args.length == 5) {
                            requestedPlayer = args[4];
                        } else {
                            sender.sendMessage(Component.text(
                                    "[InteractionVisualizer] lifetimeTicks must be an integer."));
                            return true;
                        }
                    }
                }
                Player player = performanceScenePlayer(sender, requestedPlayer);
                if (player == null) {
                    sender.sendMessage(Component.text(
                            "[InteractionVisualizer] Specify an online player for the benchmark scene."));
                    return true;
                }
                int count = parseInteger(args[3], 1);
                int requestedNearbyCount = count;
                if (args.length == 7) {
                    try {
                        requestedNearbyCount = Integer.parseInt(args[6]);
                    } catch (NumberFormatException ignored) {
                        sender.sendMessage(Component.text(
                                "[InteractionVisualizer] nearbyCount must be an integer."));
                        return true;
                    }
                    if (requestedNearbyCount < 1) {
                        sender.sendMessage(Component.text(
                                "[InteractionVisualizer] nearbyCount must be positive."));
                        return true;
                    }
                }
                int spawned = dropped
                        ? PerformanceScene.spawnDroppedItems(player, count, requestedNearbyCount, lifetime)
                        : itemDisplay || textDisplay
                        ? PerformanceScene.spawnDisplay(player, textDisplay, count, lifetime)
                        : PerformanceScene.spawn(player, moving, count, lifetime);
                String sceneName = moving ? "moving" : staticItem ? "static"
                        : itemDisplay ? "itemdisplay" : textDisplay ? "textdisplay" : "dropped";
                String entityLabel = staticItem || moving || dropped
                        ? " benchmark items" : " benchmark entities";
                String nearbyLabel = dropped && requestedNearbyCount != spawned
                        ? " (" + Math.min(spawned, requestedNearbyCount) + " nearby)" : "";
                sender.sendMessage(Component.text("[InteractionVisualizer] Spawned " + spawned + " "
                        + sceneName + entityLabel + nearbyLabel + " for " + lifetime + " ticks."));
            }
            case "clear" -> {
                String requestedOwner = args.length >= 3 ? args[2] : null;
                Player player = performanceScenePlayer(sender, requestedOwner);
                UUID ownerId = player == null ? parseUuid(requestedOwner) : player.getUniqueId();
                if (ownerId == null) {
                    sender.sendMessage(Component.text(
                            "[InteractionVisualizer] Specify an online player or owner UUID when clearing a scene from console."));
                    return true;
                }
                PerformanceScene.clear(ownerId);
                String ownerName = player == null ? ownerId.toString() : player.getName();
                sender.sendMessage(Component.text("[InteractionVisualizer] Cleared the benchmark scene for "
                        + ownerName + "."));
            }
            case "blockscene" -> handlePerformanceBlockScene(sender, args);
            default -> sender.sendMessage(Component.text(
                    "Usage: /iv perf <start|stop|status|scene|clear|blockscene>"));
        }
        return true;
    }

    private static void handlePerformanceBlockScene(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendBlockSceneUsage(sender);
            return;
        }
        String action = args[2].toLowerCase();
        try {
            switch (action) {
                case "create" -> createPerformanceBlockScene(sender, args);
                case "mutate" -> mutatePerformanceBlockScene(sender, args);
                case "status" -> inspectPerformanceBlockScene(sender, args, false);
                case "clear" -> inspectPerformanceBlockScene(sender, args, true);
                default -> {
                    sendBlockSceneRecord(sender, action, null,
                            "state=invalid reason=unknown_action");
                    sendBlockSceneUsage(sender);
                }
            }
        } catch (RuntimeException exception) {
            sendBlockSceneRecordForOwner(sender, action, blockSceneOwnerHint(sender, action, args),
                    "state=error error=" + summaryToken(exception.getClass().getSimpleName())
                            + " detail=" + summaryToken(exception.getMessage()));
        }
    }

    private static void createPerformanceBlockScene(CommandSender sender, String[] args) {
        if (args.length < 5 || args.length > 6) {
            sendBlockSceneRecord(sender, "create", null, "state=invalid reason=usage");
            sender.sendMessage(Component.text(
                    "Usage: /iv perf blockscene create <idle|active|direct-write> <count> [player]"));
            return;
        }
        PerformanceBlockScene.Mode mode = performanceBlockMode(args[3]);
        if (mode == null) {
            sendBlockSceneRecord(sender, "create", null, "state=invalid reason=invalid_mode");
            return;
        }
        Integer count = performanceBlockCount(args[4]);
        if (count == null) {
            sendBlockSceneRecord(sender, "create", null,
                    "state=invalid reason=invalid_count min=1 max=" + PerformanceBlockScene.MAX_BLOCKS);
            return;
        }
        Player player = performanceScenePlayer(sender, args.length == 6 ? args[5] : null);
        if (player == null) {
            sendBlockSceneRecord(sender, "create", null, "state=invalid reason=player_not_online");
            return;
        }
        PerformanceBlockScene.Snapshot snapshot = PerformanceBlockScene.create(player, count, mode);
        sendBlockSceneRecord(sender, "create", player, snapshot.summary());
    }

    private static void mutatePerformanceBlockScene(CommandSender sender, String[] args) {
        if (args.length > 6) {
            sendBlockSceneRecord(sender, "mutate", null, "state=invalid reason=usage");
            sender.sendMessage(Component.text(
                    "Usage: /iv perf blockscene mutate [count] [idle|active|direct-write] [player|owner-uuid]"));
            return;
        }

        Integer count = null;
        PerformanceBlockScene.Mode mode = null;
        String requestedPlayer = null;
        for (int index = 3; index < args.length; index++) {
            String argument = args[index];
            PerformanceBlockScene.Mode parsedMode = performanceBlockMode(argument);
            if (parsedMode != null) {
                if (mode != null) {
                    sendBlockSceneRecord(sender, "mutate", null, "state=invalid reason=duplicate_mode");
                    return;
                }
                mode = parsedMode;
                continue;
            }
            Integer parsedCount = performanceBlockCount(argument);
            if (parsedCount != null) {
                if (count != null) {
                    sendBlockSceneRecord(sender, "mutate", null, "state=invalid reason=duplicate_count");
                    return;
                }
                count = parsedCount;
                continue;
            }
            if (argument.matches("[+-]?\\d+")) {
                sendBlockSceneRecord(sender, "mutate", null,
                        "state=invalid reason=count_out_of_range min=1 max=" + PerformanceBlockScene.MAX_BLOCKS);
                return;
            }
            if (requestedPlayer == null) {
                requestedPlayer = argument;
                continue;
            }
            sendBlockSceneRecord(sender, "mutate", null, "state=invalid reason=ambiguous_arguments");
            return;
        }

        BlockSceneOwner owner = performanceBlockSceneOwner(sender, requestedPlayer);
        if (owner == null) {
            sendBlockSceneRecord(sender, "mutate", null,
                    "state=invalid reason=online_player_or_owner_uuid_required");
            return;
        }
        long mutationCommandStartNanos = System.nanoTime();
        PerformanceBlockScene.Snapshot current = PerformanceBlockScene.snapshot(owner.ownerId());
        long mutationPreflightElapsedNanos = Math.max(
                0L, System.nanoTime() - mutationCommandStartNanos);
        if (current.state() == PerformanceBlockScene.SceneState.ABSENT) {
            sendBlockSceneRecordForOwner(sender, "mutate", owner.displayName(), current.summary());
            return;
        }
        if (count != null && count > current.placedCount()) {
            sendBlockSceneRecordForOwner(sender, "mutate", owner.displayName(),
                    "state=invalid reason=count_exceeds_scene requested=" + count
                            + " max=" + current.placedCount());
            return;
        }

        PerformanceBlockScene.Snapshot snapshot;
        if (count != null && mode != null) {
            snapshot = PerformanceBlockScene.mutate(owner.ownerId(), count, mode);
        } else if (mode != null) {
            snapshot = PerformanceBlockScene.mutate(owner.ownerId(), mode);
        } else {
            snapshot = PerformanceBlockScene.mutate(owner.ownerId(),
                    count == null ? current.placedCount() : count);
        }
        long mutationCommandElapsedNanos = Math.max(
                0L, System.nanoTime() - mutationCommandStartNanos);
        String commandTiming = " mutationPreflightMs="
                + String.format(Locale.ROOT, "%.6f", mutationPreflightElapsedNanos / 1_000_000.0D)
                + " mutationCommandMs="
                + String.format(Locale.ROOT, "%.6f", mutationCommandElapsedNanos / 1_000_000.0D);
        sendBlockSceneRecordForOwner(sender, "mutate", owner.displayName(),
                snapshot.summary() + commandTiming);
    }

    private static void inspectPerformanceBlockScene(CommandSender sender, String[] args, boolean clear) {
        String action = clear ? "clear" : "status";
        if (args.length > 4) {
            sendBlockSceneRecord(sender, action, null, "state=invalid reason=usage");
            sender.sendMessage(Component.text(
                    "Usage: /iv perf blockscene " + action + " [player|owner-uuid]"));
            return;
        }
        BlockSceneOwner owner = performanceBlockSceneOwner(sender, args.length == 4 ? args[3] : null);
        if (owner == null) {
            sendBlockSceneRecord(sender, action, null,
                    "state=invalid reason=online_player_or_owner_uuid_required");
            return;
        }
        PerformanceBlockScene.Snapshot snapshot = clear
                ? PerformanceBlockScene.clear(owner.ownerId())
                : PerformanceBlockScene.snapshot(owner.ownerId());
        sendBlockSceneRecordForOwner(sender, action, owner.displayName(), snapshot.summary());
    }

    private static PerformanceBlockScene.Mode performanceBlockMode(String value) {
        try {
            return PerformanceBlockScene.Mode.parse(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Integer performanceBlockCount(String value) {
        try {
            int count = Integer.parseInt(value);
            return count >= 1 && count <= PerformanceBlockScene.MAX_BLOCKS ? count : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static void sendBlockSceneRecord(CommandSender sender, String action, Player player, String summary) {
        sendBlockSceneRecordForOwner(sender, action, player == null ? null : player.getName(), summary);
    }

    private static void sendBlockSceneRecordForOwner(CommandSender sender, String action, String owner,
                                                      String summary) {
        String record = "IV_BLOCK_SCENE action=" + summaryToken(action)
                + " owner=" + summaryToken(owner) + " " + summary;
        plugin.getLogger().info(record);
        if (sender instanceof Player) {
            sender.sendMessage(Component.text("[InteractionVisualizer] " + record));
        }
    }

    private static String summaryToken(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    private static void sendBlockSceneUsage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "Usage: /iv perf blockscene <create|mutate|status|clear>"));
    }

    private static Player performanceScenePlayer(CommandSender sender, String requestedName) {
        if (requestedName != null && !requestedName.isBlank()) {
            return Bukkit.getPlayerExact(requestedName);
        }
        return sender instanceof Player player ? player : null;
    }

    private static BlockSceneOwner performanceBlockSceneOwner(CommandSender sender, String requestedName) {
        Player online = performanceScenePlayer(sender, requestedName);
        if (online != null) {
            return new BlockSceneOwner(online.getUniqueId(), online.getName());
        }
        UUID ownerId = parseUuid(requestedName);
        return ownerId == null ? null : new BlockSceneOwner(ownerId, ownerId.toString());
    }

    private static String blockSceneOwnerHint(CommandSender sender, String action, String[] args) {
        String requested = null;
        if ("create".equals(action) && args.length >= 6) {
            requested = args[5];
        } else if (("status".equals(action) || "clear".equals(action)) && args.length >= 4) {
            requested = args[3];
        } else if ("mutate".equals(action)) {
            for (int index = 3; index < args.length; index++) {
                String argument = args[index];
                if (performanceBlockMode(argument) == null && performanceBlockCount(argument) == null
                        && !argument.matches("[+-]?\\d+")) {
                    requested = argument;
                    break;
                }
            }
        }
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return sender instanceof Player player ? player.getName() : null;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record BlockSceneOwner(UUID ownerId, String displayName) {
    }

    private static int parseInteger(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> tab = new LinkedList<>();
        if (!label.equalsIgnoreCase("interactionvisualizer") && !label.equalsIgnoreCase("iv")) {
            return tab;
        }

        switch (args.length) {
            case 0:
                if (sender.hasPermission("interactionvisualizer.reload")) {
                    tab.add("reload");
                }
                if (sender.hasPermission("interactionvisualizer.update")) {
                    tab.add("update");
                }
                if (sender.hasPermission("interactionvisualizer.toggle")) {
                    tab.add("toggle");
                }
                if (sender.hasPermission("interactionvisualizer.refresh")) {
                    tab.add("refresh");
                }
                return tab;
            case 1:
                if (sender.hasPermission("interactionvisualizer.reload")) {
                    if ("reload".startsWith(args[0].toLowerCase())) {
                        tab.add("reload");
                    }
                }
                if (sender.hasPermission("interactionvisualizer.update")) {
                    if ("update".startsWith(args[0].toLowerCase())) {
                        tab.add("update");
                    }
                }
                if (sender.hasPermission("interactionvisualizer.toggle")) {
                    if ("toggle".startsWith(args[0].toLowerCase())) {
                        tab.add("toggle");
                    }
                }
                if (sender.hasPermission("interactionvisualizer.refresh")) {
                    if ("refresh".startsWith(args[0].toLowerCase())) {
                        tab.add("refresh");
                    }
                }
                if (sender.hasPermission("interactionvisualizer.performance") && "perf".startsWith(args[0].toLowerCase())) {
                    tab.add("perf");
                }
                return tab;
            case 2:
                if (args[0].equalsIgnoreCase("perf") && sender.hasPermission("interactionvisualizer.performance")) {
                    for (String option : List.of("start", "stop", "status", "scene", "clear", "blockscene")) {
                        if (option.startsWith(args[1].toLowerCase())) {
                            tab.add(option);
                        }
                    }
                    return tab;
                }
                if (args[0].equalsIgnoreCase("toggle")) {
                    if (sender.hasPermission("interactionvisualizer.toggle")) {
                        if ("itemstand".startsWith(args[1].toLowerCase())) {
                            tab.add("itemstand");
                        }
                        if ("itemdrop".startsWith(args[1].toLowerCase())) {
                            tab.add("itemdrop");
                        }
                        if ("hologram".startsWith(args[1].toLowerCase())) {
                            tab.add("hologram");
                        }
                        if ("all".startsWith(args[1].toLowerCase())) {
                            tab.add("all");
                        }
                    }
                }
                return tab;
            case 3:
                if (args[0].equalsIgnoreCase("perf") && args[1].equalsIgnoreCase("scene")
                        && sender.hasPermission("interactionvisualizer.performance")) {
                    for (String option : List.of("static", "motion", "itemdisplay", "textdisplay", "dropped")) {
                        if (option.startsWith(args[2].toLowerCase())) {
                            tab.add(option);
                        }
                    }
                    return tab;
                }
                if (args[0].equalsIgnoreCase("perf") && args[1].equalsIgnoreCase("blockscene")
                        && sender.hasPermission("interactionvisualizer.performance")) {
                    for (String option : List.of("create", "mutate", "status", "clear")) {
                        if (option.startsWith(args[2].toLowerCase())) {
                            tab.add(option);
                        }
                    }
                    return tab;
                }
                if (args[0].equalsIgnoreCase("toggle")) {
                    if (sender.hasPermission("interactionvisualizer.toggle")) {
                        if (args[1].equalsIgnoreCase("itemstand") || args[1].equalsIgnoreCase("itemdrop") || args[1].equalsIgnoreCase("hologram") || args[1].equalsIgnoreCase("all")) {
                            for (EntryKey each : InteractionVisualizer.preferenceManager.getRegisteredEntries()) {
                                if (each.toSimpleString().toLowerCase().startsWith(args[2].toLowerCase())) {
                                    tab.add(each.toSimpleString());
                                }
                            }
                            if ("all".startsWith(args[2].toLowerCase())) {
                                tab.add("all");
                            }
                        }
                    }
                }
                return tab;
            case 4:
                if (args[0].equalsIgnoreCase("perf") && args[1].equalsIgnoreCase("blockscene")
                        && sender.hasPermission("interactionvisualizer.performance")) {
                    if (args[2].equalsIgnoreCase("create") || args[2].equalsIgnoreCase("mutate")) {
                        addMatchingBlockModes(tab, args[3]);
                    }
                    if (args[2].equalsIgnoreCase("mutate") || args[2].equalsIgnoreCase("status")
                            || args[2].equalsIgnoreCase("clear")) {
                        addMatchingPlayers(tab, args[3]);
                    }
                    return tab;
                }
                if (args[0].equalsIgnoreCase("toggle")) {
                    if (sender.hasPermission("interactionvisualizer.toggle")) {
                        if (args[1].equalsIgnoreCase("itemstand") || args[1].equalsIgnoreCase("itemdrop") || args[1].equalsIgnoreCase("hologram") || args[1].equalsIgnoreCase("all")) {
                            if (Boolean.TRUE.toString().toLowerCase().startsWith(args[3].toLowerCase())) {
                                tab.add(Boolean.TRUE.toString());
                            }
                            if (Boolean.FALSE.toString().toLowerCase().startsWith(args[3].toLowerCase())) {
                                tab.add(Boolean.FALSE.toString());
                            }
                        }
                    }
                }
                return tab;
            case 5:
                if (args[0].equalsIgnoreCase("perf") && args[1].equalsIgnoreCase("blockscene")
                        && args[2].equalsIgnoreCase("mutate")
                        && sender.hasPermission("interactionvisualizer.performance")) {
                    if (performanceBlockCount(args[3]) != null) {
                        addMatchingBlockModes(tab, args[4]);
                    }
                    addMatchingPlayers(tab, args[4]);
                    return tab;
                }
                if (args[0].equalsIgnoreCase("toggle")) {
                    if (sender.hasPermission("interactionvisualizer.toggle")) {
                        if (args[1].equalsIgnoreCase("itemstand") || args[1].equalsIgnoreCase("itemdrop") || args[1].equalsIgnoreCase("hologram")) {
                            if (sender.hasPermission("interactionvisualizer.toggle.others")) {
                                for (Player each : Bukkit.getOnlinePlayers()) {
                                    if (each.getName().toLowerCase().startsWith(args[4].toLowerCase())) {
                                        tab.add(each.getName());
                                    }
                                }
                            }
                        }
                    }
                }
                return tab;
            case 6:
                if (args[0].equalsIgnoreCase("perf") && args[1].equalsIgnoreCase("blockscene")
                        && sender.hasPermission("interactionvisualizer.performance")) {
                    if (args[2].equalsIgnoreCase("create")
                            || args[2].equalsIgnoreCase("mutate")) {
                        addMatchingPlayers(tab, args[5]);
                    }
                    return tab;
                }
                return tab;
            default:
                return tab;
        }
    }

    private static void addMatchingBlockModes(List<String> tab, String prefix) {
        for (String option : List.of("idle", "active", "direct-write")) {
            if (option.startsWith(prefix.toLowerCase())) {
                tab.add(option);
            }
        }
    }

    private static void addMatchingPlayers(List<String> tab, String prefix) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase().startsWith(prefix.toLowerCase())) {
                tab.add(player.getName());
            }
        }
    }

}
