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

package com.loohp.interactionvisualizer.utils;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.awt.Color;
import java.util.Map;

public class ColorUtils {

    private static final Map<Character, NamedTextColor> LEGACY_COLORS = Map.ofEntries(
            Map.entry('0', NamedTextColor.BLACK),
            Map.entry('1', NamedTextColor.DARK_BLUE),
            Map.entry('2', NamedTextColor.DARK_GREEN),
            Map.entry('3', NamedTextColor.DARK_AQUA),
            Map.entry('4', NamedTextColor.DARK_RED),
            Map.entry('5', NamedTextColor.DARK_PURPLE),
            Map.entry('6', NamedTextColor.GOLD),
            Map.entry('7', NamedTextColor.GRAY),
            Map.entry('8', NamedTextColor.DARK_GRAY),
            Map.entry('9', NamedTextColor.BLUE),
            Map.entry('a', NamedTextColor.GREEN),
            Map.entry('b', NamedTextColor.AQUA),
            Map.entry('c', NamedTextColor.RED),
            Map.entry('d', NamedTextColor.LIGHT_PURPLE),
            Map.entry('e', NamedTextColor.YELLOW),
            Map.entry('f', NamedTextColor.WHITE)
    );

    public static TextColor toTextColor(String str) {
        try {
            if (str == null || str.length() < 2) {
                return null;
            }
            if (Character.toLowerCase(str.charAt(1)) == 'x' && str.length() >= 14) {
                int rgb = Integer.parseInt("" + str.charAt(3) + str.charAt(5) + str.charAt(7) + str.charAt(9) + str.charAt(11) + str.charAt(13), 16);
                return TextColor.color(rgb);
            } else {
                return LEGACY_COLORS.get(Character.toLowerCase(str.charAt(1)));
            }
        } catch (IndexOutOfBoundsException | NumberFormatException e) {
            return null;
        }
    }

    public static Color getColor(TextColor textColor) {
        return textColor == null ? Color.WHITE : new Color(textColor.value());
    }

    public static NamedTextColor getLegacyTextColor(Color color) {
        return color == null ? NamedTextColor.WHITE : NamedTextColor.nearestTo(TextColor.color(color.getRGB() & 0xFFFFFF));
    }

    public static Color hex2Rgb(String colorStr) {
        return new Color(Integer.valueOf(colorStr.substring(1, 3), 16), Integer.valueOf(colorStr.substring(3, 5), 16),
                         Integer.valueOf(colorStr.substring(5, 7), 16));
    }

    public static String rgb2Hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    public static Color getFirstColor(String str) {
        String colorStr = ChatColorUtils.getFirstColors(str);
        if (colorStr.length() > 1) {
            TextColor textColor = toTextColor(colorStr);
            if (textColor != null) {
                return getColor(textColor);
            }
        }
        return null;
    }

    public static NamedTextColor toNamedTextColor(TextColor color) {
        return color == null ? NamedTextColor.WHITE : NamedTextColor.nearestTo(color);
    }

}
