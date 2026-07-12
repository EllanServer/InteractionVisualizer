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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatColorUtils {

    public static final char COLOR_CHAR = '\u00a7';

    private static final Pattern COLOR_FORMATTING = Pattern.compile("(?=(?<!\\\\)|(?<=\\\\\\\\))\\[[^\\]]*?color=#[0-9a-fA-F]{6}[^\\[]*?\\]");
    private static final Pattern COLOR_ESCAPE = Pattern.compile("\\\\\\[ *?color=#[0-9a-fA-F]{6} *?\\]");

    private static final Pattern COLOR_CODE_FORMAT = Pattern.compile("(?i)\u00a7[0-9a-fk-orx]");
    private static final Pattern COLOR_HEX_FORMAT_BUKKIT = Pattern.compile("^#([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])$");

    public static String stripColor(String string) {
        return string == null ? null : COLOR_CODE_FORMAT.matcher(string).replaceAll("");
    }

    public static String filterIllegalColorCodes(String string) {
        return string == null ? null : string.replaceAll("(?i)\u00a7[^0-9a-fk-orx]", "");
    }

    public static String getLastColors(String input) {
        String result = "";

        for (int i = input.length() - 1; i > 0; i--) {
            if (input.charAt(i - 1) == '\u00a7') {
                char code = Character.toLowerCase(input.charAt(i));
                String color = String.valueOf(input.charAt(i - 1)) + input.charAt(i);
                if ((i - 13) >= 0 && isHexColorSequence(input, i - 13, COLOR_CHAR)) {
                    color = input.substring(i - 13, i + 1);
                    i -= 13;
                }
                if (isLegal(color)) {
                    result = color + result;
                    if (color.length() == 14 || isColorCode(code) || code == 'r') {
                        break;
                    }
                }
            }
        }

        return result;
    }

    public static String getFirstColors(String input) {
        String result = "";
        boolean found = false;

        if (input.length() < 2) {
            return "";
        }

        int i = 0;
        while (i < input.length() - 1) {
            if (input.charAt(i) != COLOR_CHAR) {
                if (found) {
                    break;
                }
                i++;
                continue;
            }

            int length = isHexColorSequence(input, i, COLOR_CHAR) ? 14 : 2;
            String color = input.substring(i, i + length);
            if (isLegal(color)) {
                if (!found) {
                    found = true;
                    result = color;
                } else if (Character.toLowerCase(color.charAt(1)) == 'x' || isColorCode(color.charAt(1))) {
                    result = color;
                } else {
                    result = result + color;
                }
                i += length;
            } else if (found) {
                break;
            } else {
                i++;
            }
        }

        return result;
    }

    public static boolean isColorCode(char code) {
        char normalized = Character.toLowerCase(code);
        return normalized >= '0' && normalized <= '9' || normalized >= 'a' && normalized <= 'f';
    }

    public static boolean isLegal(String color) {
        if (color == null || color.length() < 2 || color.charAt(0) != COLOR_CHAR) {
            return false;
        }
        if (color.length() == 2 && isLegacyCode(color.charAt(1))) {
            return true;
        }
        return color.length() == 14 && isHexColorSequence(color, 0, COLOR_CHAR);
    }

    public static String addColorToEachWord(String text, String leadingColor) {
        StringBuilder sb = new StringBuilder();
        text = leadingColor + text;
        do {
            int pos = text.indexOf(" ") + 1;
            pos = pos <= 0 ? text.length() : pos;
            String before = leadingColor + text.substring(0, pos);
            sb.append(before);
            text = text.substring(pos);
            leadingColor = getLastColors(before);
        } while (text.length() > 0 && !text.equals(leadingColor));
        return sb.toString();
    }

    public static String hexToColorCode(String hex) {
        if (hex == null) {
            return null;
        }

        Matcher matcher = COLOR_HEX_FORMAT_BUKKIT.matcher(hex);
        if (matcher.find()) {
            return COLOR_CHAR + "x" + COLOR_CHAR + matcher.group(1) + COLOR_CHAR + matcher.group(2) + COLOR_CHAR + matcher.group(3) + COLOR_CHAR + matcher.group(4) + COLOR_CHAR + matcher.group(5) + COLOR_CHAR + matcher.group(6);
        } else {
            return COLOR_CHAR + "x" + COLOR_CHAR + "F" + COLOR_CHAR + "F" + COLOR_CHAR + "F" + COLOR_CHAR + "F" + COLOR_CHAR + "F" + COLOR_CHAR + "F";
        }
    }

    public static String translatePluginColorFormatting(String text) {
        while (true) {
            Matcher matcher = COLOR_FORMATTING.matcher(text);

            if (matcher.find()) {
                String formattedColor = matcher.group().toLowerCase();
                int start = matcher.start();
                int pos = formattedColor.indexOf("color");
                int absPos = text.indexOf("color", start);
                int end = matcher.end();

                if (pos < 0) {
                    continue;
                }

                String colorCode = hexToColorCode(formattedColor.substring(pos + 6, pos + 13));

                StringBuilder sb = new StringBuilder(text);
                sb.insert(end, colorCode);

                sb.delete(absPos, absPos + 13);

                while (sb.charAt(absPos) == ',' || sb.charAt(absPos) == ' ') {
                    sb.deleteCharAt(absPos);
                }

                while (sb.charAt(absPos - 1) == ',' || sb.charAt(absPos - 1) == ' ') {
                    sb.deleteCharAt(absPos - 1);
                    absPos--;
                }

                if (sb.charAt(absPos) == ']' && sb.charAt(absPos - 1) == '[') {
                    sb.deleteCharAt(absPos - 1);
                    sb.deleteCharAt(absPos - 1);

                    if (absPos > 2 && sb.charAt(absPos - 2) == '\\' && sb.charAt(absPos - 3) == '\\') {
                        sb.deleteCharAt(absPos - 2);
                    }
                }

                text = sb.toString();
            } else {
                break;
            }
        }

        while (true) {
            Matcher matcher = COLOR_ESCAPE.matcher(text);
            if (matcher.find()) {
                StringBuilder sb = new StringBuilder(text);
                sb.deleteCharAt(matcher.start());
                text = sb.toString();
            } else {
                break;
            }
        }

        return text;
    }

    public static String translateAlternateColorCodes(char code, String text) {
        if (text == null) {
            return text;
        }

        if (text.length() < 2) {
            return text;
        }

        char[] characters = translatePluginColorFormatting(text).toCharArray();
        for (int i = 0; i < characters.length - 1; i++) {
            if (characters[i] != code) {
                continue;
            }
            if (Character.toLowerCase(characters[i + 1]) == 'x' && isHexColorSequence(characters, i, code)) {
                for (int marker = i; marker <= i + 12; marker += 2) {
                    characters[marker] = COLOR_CHAR;
                }
                i += 13;
            } else if (isLegacyCode(characters[i + 1])) {
                characters[i] = COLOR_CHAR;
                characters[i + 1] = Character.toLowerCase(characters[i + 1]);
            }
        }
        return new String(characters);
    }

    private static boolean isLegacyCode(char code) {
        char normalized = Character.toLowerCase(code);
        return isColorCode(normalized) || normalized >= 'k' && normalized <= 'o' || normalized == 'r';
    }

    private static boolean isHexColorSequence(String text, int start, char marker) {
        if (start < 0 || start + 14 > text.length() || text.charAt(start) != marker || Character.toLowerCase(text.charAt(start + 1)) != 'x') {
            return false;
        }
        for (int i = start + 2; i < start + 14; i += 2) {
            if (text.charAt(i) != marker || Character.digit(text.charAt(i + 1), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexColorSequence(char[] text, int start, char marker) {
        if (start < 0 || start + 14 > text.length || text[start] != marker || Character.toLowerCase(text[start + 1]) != 'x') {
            return false;
        }
        for (int i = start + 2; i < start + 14; i += 2) {
            if (text[i] != marker || Character.digit(text[i + 1], 16) < 0) {
                return false;
            }
        }
        return true;
    }

}
