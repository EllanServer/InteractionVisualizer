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

package com.loohp.interactionvisualizer.managers;

import com.loohp.interactionvisualizer.InteractionVisualizer;
import com.loohp.interactionvisualizer.api.VisualizerInteractDisplay;
import com.loohp.interactionvisualizer.api.VisualizerRunnableDisplay;
import com.loohp.interactionvisualizer.blocks.AnvilDisplay;
import com.loohp.interactionvisualizer.blocks.BannerDisplay;
import com.loohp.interactionvisualizer.blocks.BarrelDisplay;
import com.loohp.interactionvisualizer.blocks.BeaconDisplay;
import com.loohp.interactionvisualizer.blocks.BeeHiveDisplay;
import com.loohp.interactionvisualizer.blocks.BeeNestDisplay;
import com.loohp.interactionvisualizer.blocks.BlastFurnaceDisplay;
import com.loohp.interactionvisualizer.blocks.BrewingStandDisplay;
import com.loohp.interactionvisualizer.blocks.CampfireDisplay;
import com.loohp.interactionvisualizer.blocks.CartographyTableDisplay;
import com.loohp.interactionvisualizer.blocks.ChestDisplay;
import com.loohp.interactionvisualizer.blocks.ConduitDisplay;
import com.loohp.interactionvisualizer.blocks.CrafterDisplay;
import com.loohp.interactionvisualizer.blocks.CraftingTableDisplay;
import com.loohp.interactionvisualizer.blocks.DispenserDisplay;
import com.loohp.interactionvisualizer.blocks.DoubleChestDisplay;
import com.loohp.interactionvisualizer.blocks.DropperDisplay;
import com.loohp.interactionvisualizer.blocks.EnchantmentTableDisplay;
import com.loohp.interactionvisualizer.blocks.EnderchestDisplay;
import com.loohp.interactionvisualizer.blocks.FurnaceDisplay;
import com.loohp.interactionvisualizer.blocks.GrindstoneDisplay;
import com.loohp.interactionvisualizer.blocks.HopperDisplay;
import com.loohp.interactionvisualizer.blocks.JukeBoxDisplay;
import com.loohp.interactionvisualizer.blocks.LecternDisplay;
import com.loohp.interactionvisualizer.blocks.LoomDisplay;
import com.loohp.interactionvisualizer.blocks.NoteBlockDisplay;
import com.loohp.interactionvisualizer.blocks.ShulkerBoxDisplay;
import com.loohp.interactionvisualizer.blocks.SmithingTableDisplay;
import com.loohp.interactionvisualizer.blocks.SmokerDisplay;
import com.loohp.interactionvisualizer.blocks.SoulCampfireDisplay;
import com.loohp.interactionvisualizer.blocks.SpawnerDisplay;
import com.loohp.interactionvisualizer.blocks.StonecutterDisplay;
import com.loohp.interactionvisualizer.debug.Debug;
import com.loohp.interactionvisualizer.entities.DroppedItemDisplay;
import com.loohp.interactionvisualizer.entities.VillagerDisplay;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.updater.Updater;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import com.loohp.interactionvisualizer.config.SparrowConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TaskManager {

    public static Plugin plugin = InteractionVisualizer.plugin;
    public static boolean anvil;
    public static boolean banner;
    public static boolean barrel;
    public static boolean beacon;
    public static boolean beehive;
    public static boolean beenest;
    public static boolean blastfurnace;
    public static boolean brewingstand;
    public static boolean campfire;
    public static boolean cartographytable;
    public static boolean chest;
    public static boolean conduit;
    public static boolean craftingtable;
    public static boolean crafter;
    public static boolean dispenser;
    public static boolean doublechest;
    public static boolean dropper;
    public static boolean enchantmenttable;
    public static boolean enderchest;
    public static boolean furnace;
    public static boolean grindstone;
    public static boolean hopper;
    public static boolean jukebox;
    public static boolean lectern;
    public static boolean loom;
    public static boolean noteblock;
    public static boolean shulkerbox;
    public static boolean smoker;
    public static boolean soulcampfire;
    public static boolean spawner;
    public static boolean stonecutter;
    public static boolean smithingtable;

    public static boolean item;
    public static boolean villager;

    public static Map<InventoryType, List<VisualizerInteractDisplay>> processes = new ConcurrentHashMap<>();
    public static List<VisualizerRunnableDisplay> runnables = new ArrayList<>();
    static final long INVENTORY_OPEN_PROCESS_DELAY_TICKS = 1L;
    static final long INVENTORY_PROCESS_DELAY_TICKS = 2L;
    private static final Map<UUID, Object> pendingInventoryOpenProcesses = new ConcurrentHashMap<>();
    private static final Map<UUID, Object> pendingInventoryRefreshes = new ConcurrentHashMap<>();

    @SuppressWarnings("deprecation")
    public static void setup() {
        pendingInventoryOpenProcesses.clear();
        pendingInventoryRefreshes.clear();
        anvil = false;
        banner = false;
        barrel = false;
        beacon = false;
        beehive = false;
        beenest = false;
        blastfurnace = false;
        brewingstand = false;
        campfire = false;
        cartographytable = false;
        chest = false;
        conduit = false;
        craftingtable = false;
        crafter = false;
        dispenser = false;
        doublechest = false;
        dropper = false;
        enchantmenttable = false;
        enderchest = false;
        furnace = false;
        grindstone = false;
        hopper = false;
        jukebox = false;
        lectern = false;
        loom = false;
        noteblock = false;
        shulkerbox = false;
        smoker = false;
        soulcampfire = false;
        spawner = false;
        stonecutter = false;
        smithingtable = false;

        item = false;
        villager = false;

		/*
		HandlerList.unregisterAll(plugin);
		for (int taskid : tasks) {
			Scheduler.cancelTask(taskid);
		}
		tasks.clear();
		*/

        List<EntryKey> keys = new ArrayList<>();
        EventDrivenBlockUpdateListener eventDrivenBlockUpdateListener =
                InteractionVisualizer.eventDrivenBlockUpdates ? new EventDrivenBlockUpdateListener() : null;

        Bukkit.getPluginManager().registerEvents(new Debug(), plugin);
        Bukkit.getPluginManager().registerEvents(new Updater(), plugin);
        Bukkit.getPluginManager().registerEvents(new com.loohp.interactionvisualizer.listeners.Events(), plugin);
        Bukkit.getPluginManager().registerEvents(new DisplayManager(), plugin);

        for (InventoryType type : InventoryType.values()) {
            processes.put(type, new ArrayList<>());
        }

        if (getConfig().getBoolean("Blocks.CraftingTable.Enabled")) {
            CraftingTableDisplay ctd = new CraftingTableDisplay();
            keys.add(ctd.registerNative(InventoryType.WORKBENCH));
            Bukkit.getPluginManager().registerEvents(ctd, plugin);
            craftingtable = true;
        }

        if (getConfig().getBoolean("Blocks.Crafter.Enabled")) {
            CrafterDisplay cd = new CrafterDisplay();
            keys.add(cd.registerNative());
            Bukkit.getPluginManager().registerEvents(cd, plugin);
            crafter = true;
        }

        if (getConfig().getBoolean("Blocks.Loom.Enabled")) {
            LoomDisplay ld = new LoomDisplay();
            keys.add(ld.registerNative(InventoryType.LOOM));
            Bukkit.getPluginManager().registerEvents(ld, plugin);
            loom = true;
        }

        if (getConfig().getBoolean("Blocks.EnchantmentTable.Enabled")) {
            EnchantmentTableDisplay etd = new EnchantmentTableDisplay();
            keys.add(etd.registerNative(InventoryType.ENCHANTING));
            Bukkit.getPluginManager().registerEvents(etd, plugin);
            enchantmenttable = true;
        }

        if (getConfig().getBoolean("Blocks.CartographyTable.Enabled")) {
            CartographyTableDisplay ctd = new CartographyTableDisplay();
            keys.add(ctd.registerNative(InventoryType.CARTOGRAPHY));
            Bukkit.getPluginManager().registerEvents(ctd, plugin);
            cartographytable = true;
        }

        if (getConfig().getBoolean("Blocks.Anvil.Enabled")) {
            AnvilDisplay ad = new AnvilDisplay();
            keys.add(ad.registerNative(InventoryType.ANVIL));
            Bukkit.getPluginManager().registerEvents(ad, plugin);
            anvil = true;
        }

        if (getConfig().getBoolean("Blocks.Grindstone.Enabled")) {
            GrindstoneDisplay gd = new GrindstoneDisplay();
            keys.add(gd.registerNative(InventoryType.GRINDSTONE));
            Bukkit.getPluginManager().registerEvents(gd, plugin);
            grindstone = true;
        }

        if (getConfig().getBoolean("Blocks.Stonecutter.Enabled")) {
            StonecutterDisplay sd = new StonecutterDisplay();
            keys.add(sd.registerNative(InventoryType.STONECUTTER));
            Bukkit.getPluginManager().registerEvents(sd, plugin);
            stonecutter = true;
        }

        if (getConfig().getBoolean("Blocks.BrewingStand.Enabled")) {
            BrewingStandDisplay bsd = new BrewingStandDisplay();
            keys.add(bsd.registerNative());
            Bukkit.getPluginManager().registerEvents(bsd, plugin);
            brewingstand = true;
        }

        if (getConfig().getBoolean("Blocks.Chest.Enabled")) {
            ChestDisplay cd = new ChestDisplay();
            keys.add(cd.registerNative());
            Bukkit.getPluginManager().registerEvents(cd, plugin);
            chest = true;
        }

        if (getConfig().getBoolean("Blocks.DoubleChest.Enabled")) {
            DoubleChestDisplay dcd = new DoubleChestDisplay();
            keys.add(dcd.registerNative());
            Bukkit.getPluginManager().registerEvents(dcd, plugin);
            doublechest = true;
        }

        if (getConfig().getBoolean("Blocks.Furnace.Enabled")) {
            FurnaceDisplay fd = new FurnaceDisplay();
            keys.add(fd.registerNative());
            Bukkit.getPluginManager().registerEvents(fd, plugin);
            if (eventDrivenBlockUpdateListener != null) {
                eventDrivenBlockUpdateListener.add(fd);
            }
            furnace = true;
        }

        if (getConfig().getBoolean("Blocks.BlastFurnace.Enabled")) {
            BlastFurnaceDisplay bfd = new BlastFurnaceDisplay();
            keys.add(bfd.registerNative());
            Bukkit.getPluginManager().registerEvents(bfd, plugin);
            if (eventDrivenBlockUpdateListener != null) {
                eventDrivenBlockUpdateListener.add(bfd);
            }
            blastfurnace = true;
        }

        if (getConfig().getBoolean("Blocks.Smoker.Enabled")) {
            SmokerDisplay sd = new SmokerDisplay();
            keys.add(sd.registerNative());
            Bukkit.getPluginManager().registerEvents(sd, plugin);
            if (eventDrivenBlockUpdateListener != null) {
                eventDrivenBlockUpdateListener.add(sd);
            }
            smoker = true;
        }

        if (getConfig().getBoolean("Blocks.EnderChest.Enabled")) {
            EnderchestDisplay ed = new EnderchestDisplay();
            keys.add(ed.registerNative());
            Bukkit.getPluginManager().registerEvents(ed, plugin);
            enderchest = true;
        }

        if (getConfig().getBoolean("Blocks.ShulkerBox.Enabled")) {
            ShulkerBoxDisplay sbd = new ShulkerBoxDisplay();
            keys.add(sbd.registerNative());
            Bukkit.getPluginManager().registerEvents(sbd, plugin);
            shulkerbox = true;
        }

        if (getConfig().getBoolean("Blocks.Dispenser.Enabled")) {
            DispenserDisplay dd = new DispenserDisplay();
            keys.add(dd.registerNative());
            Bukkit.getPluginManager().registerEvents(dd, plugin);
            dispenser = true;
        }

        if (getConfig().getBoolean("Blocks.Dropper.Enabled")) {
            DropperDisplay dd = new DropperDisplay();
            keys.add(dd.registerNative());
            Bukkit.getPluginManager().registerEvents(dd, plugin);
            dropper = true;
        }

        if (getConfig().getBoolean("Blocks.Hopper.Enabled")) {
            HopperDisplay hd = new HopperDisplay();
            keys.add(hd.registerNative());
            Bukkit.getPluginManager().registerEvents(hd, plugin);
            hopper = true;
        }

        if (getConfig().getBoolean("Blocks.Beacon.Enabled")) {
            BeaconDisplay bd = new BeaconDisplay();
            keys.add(bd.registerNative());
            Bukkit.getPluginManager().registerEvents(bd, plugin);
            beacon = true;
        }

        if (getConfig().getBoolean("Blocks.NoteBlock.Enabled")) {
            NoteBlockDisplay nbd = new NoteBlockDisplay();
            keys.add(nbd.registerNative());
            Bukkit.getPluginManager().registerEvents(nbd, plugin);
            noteblock = true;
        }

        if (getConfig().getBoolean("Blocks.JukeBox.Enabled")) {
            JukeBoxDisplay jbd = new JukeBoxDisplay();
            keys.add(jbd.registerNative());
            Bukkit.getPluginManager().registerEvents(jbd, plugin);
            jukebox = true;
        }

        if (getConfig().getBoolean("Blocks.SmithingTable.Enabled")) {
            SmithingTableDisplay std = new SmithingTableDisplay();
            keys.add(std.registerNative(InventoryType.SMITHING));
            Bukkit.getPluginManager().registerEvents(std, plugin);
            smithingtable = true;
        }

        if (getConfig().getBoolean("Blocks.BeeNest.Enabled")) {
            BeeNestDisplay bnd = new BeeNestDisplay();
            keys.add(bnd.registerNative());
            Bukkit.getPluginManager().registerEvents(bnd, plugin);
            if (eventDrivenBlockUpdateListener != null) {
                eventDrivenBlockUpdateListener.add(bnd);
            }
            beenest = true;
        }

        if (getConfig().getBoolean("Blocks.BeeHive.Enabled")) {
            BeeHiveDisplay bhd = new BeeHiveDisplay();
            keys.add(bhd.registerNative());
            Bukkit.getPluginManager().registerEvents(bhd, plugin);
            if (eventDrivenBlockUpdateListener != null) {
                eventDrivenBlockUpdateListener.add(bhd);
            }
            beehive = true;
        }

        if (getConfig().getBoolean("Blocks.Lectern.Enabled")) {
            LecternDisplay ld = new LecternDisplay();
            keys.add(ld.registerNative());
            Bukkit.getPluginManager().registerEvents(ld, plugin);
            lectern = true;
        }

        if (getConfig().getBoolean("Blocks.Campfire.Enabled")) {
            CampfireDisplay cd = new CampfireDisplay();
            keys.add(cd.registerNative());
            Bukkit.getPluginManager().registerEvents(cd, plugin);
            campfire = true;
        }

        if (getConfig().getBoolean("Blocks.SoulCampfire.Enabled")) {
            SoulCampfireDisplay scd = new SoulCampfireDisplay();
            keys.add(scd.registerNative());
            Bukkit.getPluginManager().registerEvents(scd, plugin);
            soulcampfire = true;
        }

        if (getConfig().getBoolean("Blocks.Spawner.Enabled")) {
            SpawnerDisplay sd = new SpawnerDisplay();
            keys.add(sd.registerNative());
            Bukkit.getPluginManager().registerEvents(sd, plugin);
            spawner = true;
        }

        if (getConfig().getBoolean("Blocks.Conduit.Enabled")) {
            ConduitDisplay cd = new ConduitDisplay();
            keys.add(cd.registerNative());
            Bukkit.getPluginManager().registerEvents(cd, plugin);
            conduit = true;
        }

        if (getConfig().getBoolean("Blocks.Banner.Enabled")) {
            BannerDisplay bd = new BannerDisplay();
            keys.add(bd.registerNative());
            Bukkit.getPluginManager().registerEvents(bd, plugin);
            banner = true;
        }

        if (getConfig().getBoolean("Blocks.Barrel.Enabled")) {
            BarrelDisplay bd = new BarrelDisplay();
            keys.add(bd.registerNative());
            Bukkit.getPluginManager().registerEvents(bd, plugin);
            barrel = true;
        }

        if (getConfig().getBoolean("Entities.Item.Enabled")) {
            DroppedItemDisplay id = new DroppedItemDisplay();
            keys.add(id.registerNative());
            Bukkit.getPluginManager().registerEvents(id, plugin);
            item = true;
        }

        if (getConfig().getBoolean("Entities.Villager.Enabled")) {
            VillagerDisplay vd = new VillagerDisplay();
            keys.add(vd.registerNative());
            Bukkit.getPluginManager().registerEvents(vd, plugin);
            villager = true;
        }

        if (eventDrivenBlockUpdateListener != null && !eventDrivenBlockUpdateListener.isEmpty()) {
            Bukkit.getPluginManager().registerEvents(eventDrivenBlockUpdateListener, plugin);
        }

        InteractionVisualizer.preferenceManager.registerEntry(keys);
        InteractionVisualizer.lightManager.run();
        DisplayManager.update();
    }

    public static void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            processOpenInventory(player);
        }
    }

    /**
     * Stops every scheduler task owned by this plugin and releases the display
     * registries that otherwise retain a full graph of listeners and task handles
     * until the old plugin class loader is collected.
     */
    public static void shutdown() {
        try {
            if (plugin != null) {
                Bukkit.getScheduler().cancelTasks(plugin);
            }
        } finally {
            clearRuntimeState();
        }
    }

    static void clearRuntimeState() {
        pendingInventoryOpenProcesses.clear();
        pendingInventoryRefreshes.clear();
        for (List<VisualizerInteractDisplay> displays : processes.values()) {
            displays.clear();
        }
        processes.clear();
        runnables.clear();
    }

    public static void processOpenInventory(Player player) {
        if (!player.isOnline()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!markInventoryOpenProcessQueued(playerId)) {
            return;
        }
        Object request = pendingInventoryOpenProcesses.get(playerId);
        try {
            Scheduler.runTaskLater(plugin, () -> {
                if (pendingInventoryOpenProcesses.remove(playerId, request)) {
                    processCurrentInventory(player, true);
                }
            }, INVENTORY_OPEN_PROCESS_DELAY_TICKS, player);
        } catch (RuntimeException exception) {
            pendingInventoryOpenProcesses.remove(playerId, request);
            throw exception;
        }
    }

    public static void refreshOpenInventory(Player player) {
        if (!player.isOnline()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!markInventoryRefreshQueued(playerId)) {
            return;
        }
        Object request = pendingInventoryRefreshes.get(playerId);
        try {
            Scheduler.runTaskLater(plugin, () -> {
                if (pendingInventoryRefreshes.remove(playerId, request)) {
                    processCurrentInventory(player, false);
                }
            }, INVENTORY_PROCESS_DELAY_TICKS, player);
        } catch (RuntimeException exception) {
            pendingInventoryRefreshes.remove(playerId, request);
            throw exception;
        }
    }

    static boolean markInventoryOpenProcessQueued(UUID playerId) {
        // The one-tick open callback observes the same or newer state and also
        // includes every native display, so an older two-tick refresh is redundant.
        pendingInventoryRefreshes.remove(playerId);
        return pendingInventoryOpenProcesses.putIfAbsent(playerId, new Object()) == null;
    }

    static boolean markInventoryRefreshQueued(UUID playerId) {
        if (pendingInventoryOpenProcesses.containsKey(playerId)) {
            return false;
        }
        return pendingInventoryRefreshes.putIfAbsent(playerId, new Object()) == null;
    }

    public static void clearPendingInventoryProcess(UUID playerId) {
        pendingInventoryOpenProcesses.remove(playerId);
        pendingInventoryRefreshes.remove(playerId);
    }

    static void processCurrentInventory(Player player) {
        processCurrentInventory(player, true);
    }

    static void processCurrentInventory(Player player, boolean includeCustomDisplays) {
        if (!player.isOnline()) {
            return;
        }
        Inventory inventory = player.getOpenInventory().getTopInventory();
        processInventoryDisplays(player, inventory.getType(), includeCustomDisplays);
    }

    static void processInventoryDisplays(Player player, InventoryType type) {
        processInventoryDisplays(player, type, true);
    }

    static void processInventoryDisplays(Player player, InventoryType type, boolean includeCustomDisplays) {
        if (!hasInventoryDisplays(type)) {
            return;
        }
        for (VisualizerInteractDisplay display : processes.getOrDefault(type, List.of())) {
            if (includeCustomDisplays || isNativeInventoryDisplay(display)) {
                display.process(player);
            }
        }
    }

    public static boolean hasInventoryDisplays(InventoryType type) {
        if (type == null || type == InventoryType.CRAFTING || type == InventoryType.CREATIVE) {
            return false;
        }
        List<VisualizerInteractDisplay> displays = processes.get(type);
        return displays != null && !displays.isEmpty();
    }

    public static boolean hasNativeInventoryDisplays(InventoryType type) {
        if (type == null || type == InventoryType.CRAFTING || type == InventoryType.CREATIVE) {
            return false;
        }
        for (VisualizerInteractDisplay display : processes.getOrDefault(type, List.of())) {
            if (isNativeInventoryDisplay(display)) {
                return true;
            }
        }
        return false;
    }

    static boolean isNativeInventoryDisplay(VisualizerInteractDisplay display) {
        return display.key().isNative();
    }

    private static SparrowConfiguration getConfig() {
        return InteractionVisualizer.plugin.getConfiguration();
    }

}
