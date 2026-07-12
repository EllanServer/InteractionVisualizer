/*
 * This file is part of InteractionVisualizer.
 *
 * Copyright (C) 2026. Contributors
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

package com.loohp.interactionvisualizer.config;

import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.YamlDocument;
import net.momirealms.sparrow.yaml.node.SectionNode;
import net.momirealms.sparrow.yaml.node.YamlNode;
import net.momirealms.sparrow.yaml.route.Route;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A small, cached compatibility surface over Sparrow YAML.
 *
 * <p>The YAML tree is only traversed when a file is loaded, reloaded, or
 * mutated. Normal plugin reads use an immutable flat snapshot, avoiding YAML
 * node traversal and path parsing in feature polling loops.</p>
 */
public final class SparrowConfiguration {

    private static final SparrowYaml YAML = SparrowYaml.builder()
            .setAllowDuplicateKeys(false)
            .setAllowObjectKeys(false)
            .setCodePointLimit(16 * 1024 * 1024)
            .build();

    private final File file;
    private volatile YamlDocument document;
    private volatile Snapshot snapshot;

    public SparrowConfiguration(File file) throws IOException {
        this.file = Objects.requireNonNull(file, "file");
        this.document = YAML.load(file);
        this.snapshot = createSnapshot(document);
    }

    public SparrowConfiguration(File file, InputStream defaults, boolean refreshComments) throws IOException {
        this.file = Objects.requireNonNull(file, "file");
        this.document = YAML.load(file);

        try (InputStream input = Objects.requireNonNull(defaults, "defaults")) {
            YamlDocument defaultDocument = YAML.load(input);
            mergeSection(defaultDocument, refreshComments);
        }

        this.snapshot = createSnapshot(document);
        save();
    }

    public boolean contains(String path) {
        Snapshot current = snapshot;
        return current.values().containsKey(path) || current.sections().contains(path);
    }

    public boolean isConfigurationSection(String path) {
        return snapshot.sections().contains(path);
    }

    public Section getConfigurationSection(String path) {
        return isConfigurationSection(path) ? new Section(path) : null;
    }

    public Object get(String path) {
        return snapshot.values().get(path);
    }

    public String getString(String path) {
        return getString(path, null);
    }

    public String getString(String path, String defaultValue) {
        Object value = get(path);
        return value == null ? defaultValue : String.valueOf(value);
    }

    public boolean getBoolean(String path) {
        Object value = get(path);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    public int getInt(String path) {
        Object value = get(path);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public long getLong(String path) {
        Object value = get(path);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public double getDouble(String path) {
        Object value = get(path);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0D : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0.0D;
        }
    }

    public List<?> getList(String path) {
        Object value = get(path);
        return value instanceof List<?> list ? list : Collections.emptyList();
    }

    public List<String> getStringList(String path) {
        Object value = get(path);
        if (!(value instanceof Collection<?> collection)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(collection.size());
        for (Object element : collection) {
            if (element != null) {
                result.add(String.valueOf(element));
            }
        }
        return List.copyOf(result);
    }

    public Map<String, Object> getValues(boolean deep) {
        return valuesForPrefix("", deep);
    }

    public synchronized void set(String path, Object value) {
        Route route = route(path);
        if (value == null) {
            document.removeNode(route);
        } else {
            document.set(route, value);
        }
        snapshot = createSnapshot(document);
    }

    public synchronized void reload() {
        try {
            document = YAML.load(file);
            snapshot = createSnapshot(document);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to reload " + file, exception);
        }
    }

    public synchronized void save() {
        save(file);
    }

    public synchronized void save(File destination) {
        try {
            document.save(destination);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to save " + destination, exception);
        }
    }

    private void mergeSection(SectionNode defaults, boolean refreshComments) {
        for (YamlNode<?> defaultNode : defaults.value().values()) {
            Route route = defaultNode.route();
            YamlNode<?> currentNode = document.getNodeOrNull(route);
            if (currentNode == null) {
                YamlNode<?> inserted = document.setAndGet(route, defaultNode.representValue());
                defaultNode.deepCopyCommentsTo(inserted);
                continue;
            }

            if (refreshComments) {
                defaultNode.copyCommentsTo(currentNode);
            }
            if (defaultNode instanceof SectionNode defaultSection) {
                if (currentNode instanceof SectionNode) {
                    mergeSection(defaultSection, refreshComments);
                } else {
                    document.removeNode(route);
                    YamlNode<?> inserted = document.setAndGet(route, defaultNode.representValue());
                    defaultNode.deepCopyCommentsTo(inserted);
                }
            }
        }
    }

    private Map<String, Object> valuesForPrefix(String prefix, boolean deep) {
        Snapshot current = snapshot;
        if (!deep) {
            return current.directValues().getOrDefault(prefix, Collections.emptyMap());
        }
        String prefixWithSeparator = prefix.isEmpty() ? "" : prefix + ".";
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : current.values().entrySet()) {
            String path = entry.getKey();
            if (!path.startsWith(prefixWithSeparator)) {
                continue;
            }
            String relative = path.substring(prefixWithSeparator.length());
            if (relative.isEmpty()) {
                continue;
            }
            result.put(relative, entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    private static Route route(String path) {
        if (path == null || path.isBlank()) {
            return Route.empty();
        }
        return Route.from((Object[]) path.split("\\."));
    }

    private static Snapshot createSnapshot(YamlDocument document) {
        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> sections = new LinkedHashSet<>();
        Map<String, Map<String, Object>> directValues = new LinkedHashMap<>();
        indexSection(document, "", values, sections, directValues);
        return new Snapshot(
                Collections.unmodifiableMap(values),
                Collections.unmodifiableSet(sections),
                Collections.unmodifiableMap(directValues));
    }

    private static void indexSection(SectionNode section, String prefix, Map<String, Object> values,
                                     Set<String> sections, Map<String, Map<String, Object>> directValues) {
        Map<String, Object> direct = new LinkedHashMap<>();
        for (Map.Entry<Object, YamlNode<?>> entry : section.value().entrySet()) {
            String key = String.valueOf(entry.getKey());
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            YamlNode<?> node = entry.getValue();
            Object frozen = freeze(node.representValue());
            direct.put(key, frozen);
            values.put(path, frozen);
            if (node instanceof SectionNode child) {
                sections.add(path);
                indexSection(child, path, values, sections, directValues);
            }
        }
        directValues.put(prefix, Collections.unmodifiableMap(direct));
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            for (Object element : collection) {
                copy.add(freeze(element));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    public final class Section {

        private final String prefix;

        private Section(String prefix) {
            this.prefix = prefix;
        }

        public Map<String, Object> getValues(boolean deep) {
            return valuesForPrefix(prefix, deep);
        }
    }

    private record Snapshot(Map<String, Object> values, Set<String> sections,
                            Map<String, Map<String, Object>> directValues) {
    }
}
