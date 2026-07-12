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

package com.loohp.interactionvisualizer.blocks;

import com.loohp.interactionvisualizer.InteractionVisualizer;
import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI;
import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI.Modules;
import com.loohp.interactionvisualizer.api.VisualizerRunnableDisplay;
import com.loohp.interactionvisualizer.entityholders.DisplayEntity;
import com.loohp.interactionvisualizer.managers.MusicManager;
import com.loohp.interactionvisualizer.managers.DisplayManager;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.utils.ItemNameUtils;
import com.loohp.interactionvisualizer.scheduler.ScheduledTask;
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Note.Tone;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

public class NoteBlockDisplay extends VisualizerRunnableDisplay implements Listener {

    public static final EntryKey KEY = new EntryKey("note_block");

    public ConcurrentHashMap<Block, ConcurrentHashMap<String, Object>> displayingNotes = new ConcurrentHashMap<>();

    @Override
    public EntryKey key() {
        return KEY;
    }

    @Override
    public ScheduledTask gc() {
        return null;
    }

    @Override
    public ScheduledTask run() {
        return Scheduler.runTaskTimer(InteractionVisualizer.plugin, () -> {
            Iterator<Entry<Block, ConcurrentHashMap<String, Object>>> itr = displayingNotes.entrySet().iterator();
            while (itr.hasNext()) {
                Entry<Block, ConcurrentHashMap<String, Object>> entry = itr.next();
                long unix = System.currentTimeMillis();
                long timeout = (long) entry.getValue().get("Timeout");
                if (unix > timeout) {
                    DisplayEntity stand = (DisplayEntity) entry.getValue().get("Stand");
                    Scheduler.runTask(InteractionVisualizer.plugin, () -> DisplayManager.removeDisplay(InteractionVisualizerAPI.getPlayers(), stand));
                    itr.remove();
                }
            }
        }, 0, 20);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUseNoteBlock(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        if (!block.getType().equals(Material.NOTE_BLOCK)) {
            return;
        }

        Player player = event.getPlayer();
        if (GameMode.SPECTATOR.equals(player.getGameMode())) {
            return;
        }

        boolean holdingAir = player.getEquipment().getItemInMainHand() == null || (player.getEquipment().getItemInMainHand().getType().equals(Material.AIR));
        if (player.isSneaking() && !holdingAir) {
            return;
        }

        BlockFace face = event.getBlockFace();
        Location textLocation = getFaceOffset(block, face);
        Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> {
            if (!block.getType().equals(Material.NOTE_BLOCK)) {
                return;
            }
            ConcurrentHashMap<String, Object> map = displayingNotes.get(block);
            DisplayEntity stand = map == null ? new DisplayEntity(textLocation.clone().add(0.0, -0.3, 0.0)) : (DisplayEntity) map.get("Stand");
            stand.teleport(textLocation.clone().add(0.0, -0.3, 0.0));
            setStand(stand);

            map = map == null ? new ConcurrentHashMap<String, Object>() : map;
            map.put("Stand", stand);
            map.put("Timeout", System.currentTimeMillis() + 3000);
            displayingNotes.put(block, map);

            Block topBlock = block.getRelative(BlockFace.UP);
            Collection<ItemStack> topBlockDrops;
            Component component;
            if (isHead(topBlock) && !(topBlockDrops = topBlock.getDrops()).isEmpty()) {
                ItemStack skull = topBlockDrops.iterator().next();
                component = ItemNameUtils.getDisplayName(skull);
            } else {
                NoteBlock state = (NoteBlock) block.getBlockData();
                Tone tone = state.getNote().getTone();
                String inst = MusicManager.getMusicConfig().getString("Instruments." + state.getInstrument().toString().toUpperCase());
                String toneText = tone.toString().toUpperCase();
                toneText = state.getNote().isSharped() ? toneText + "#" : toneText;
                toneText = state.getNote().getOctave() == 0 ? toneText : toneText + " ^";
                component = Component.text(inst + " ", NamedTextColor.GOLD).append(Component.text(toneText, getColor(tone)));
            }

            stand.setCustomName(component);

            DisplayManager.spawnDisplay(InteractionVisualizerAPI.getPlayerModuleList(Modules.HOLOGRAM, KEY), stand);
            DisplayManager.updateDisplay(stand);
        }, 1, block.getLocation());
    }

    public void setStand(DisplayEntity stand) {
        stand.setArms(true);
        stand.setBasePlate(false);
        stand.setMarker(true);
        stand.setGravity(false);
        stand.setSmall(true);
        stand.setSilent(true);
        stand.setInvulnerable(true);
        stand.setVisible(false);
        stand.setCustomNameVisible(true);
    }

    @SuppressWarnings("DuplicateBranchesInSwitch")
    public Location getFaceOffset(Block block, BlockFace face) {
        Location location = block.getLocation().clone().add(0.5, 0.5, 0.5);
        switch (face) {
            case DOWN:
                return location.add(0.0, -0.8, 0.0);
            case EAST:
                return location.add(0.8, 0.0, 0.0);
            case NORTH:
                return location.add(0.0, 0.0, -0.8);
            case SOUTH:
                return location.add(0.0, 0.0, 0.8);
            case UP:
                return location.add(0.0, 0.8, 0.0);
            case WEST:
                return location.add(-0.8, 0.0, 0.0);
            default:
                return location.add(0.0, 0.8, 0.0);
        }
    }

    @SuppressWarnings("DuplicateBranchesInSwitch")
    public NamedTextColor getColor(Tone tone) {
        switch (tone) {
            case A:
                return NamedTextColor.RED;
            case B:
                return NamedTextColor.GOLD;
            case C:
                return NamedTextColor.YELLOW;
            case D:
                return NamedTextColor.GREEN;
            case E:
                return NamedTextColor.AQUA;
            case F:
                return NamedTextColor.BLUE;
            case G:
                return NamedTextColor.LIGHT_PURPLE;
            default:
                return NamedTextColor.AQUA;
        }
    }

    public boolean isHead(Block block) {
        return block.getState() instanceof Skull;
    }

}
