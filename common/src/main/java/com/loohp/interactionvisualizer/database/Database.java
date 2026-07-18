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

package com.loohp.interactionvisualizer.database;

import com.loohp.interactionvisualizer.InteractionVisualizer;
import com.loohp.interactionvisualizer.api.InteractionVisualizerAPI.Modules;
import com.loohp.interactionvisualizer.managers.PerformanceMetrics;
import com.loohp.interactionvisualizer.objectholders.EntryKey;
import com.loohp.interactionvisualizer.utils.ArrayUtils;
import com.loohp.interactionvisualizer.utils.BitSetUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Persistent preference connection used exclusively by PreferenceManager's
 * single I/O queue. A failed JDBC operation discards the connection and
 * retries the complete idempotent operation once on a fresh connection.
 */
public final class Database {

    public static final String EMPTY_BITSET = "0";
    public static final Pattern VALID_BITSET = Pattern.compile("^[0-9]*$");

    private static final String PREFERENCE_TABLE = "USER_PERFERENCES";
    private static final String INDEX_MAPPING_TABLE = "INDEX_MAPPING";
    private static final int QUERY_TIMEOUT_SECONDS = 10;
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;
    private static final Object DATABASE_LOCK = new Object();

    public static boolean isMYSQL;

    private static Connection connection;
    private static String host;
    private static String database;
    private static String username;
    private static String password;
    private static int port;
    private static boolean configured;

    private Database() {
    }

    public static void setup() {
        synchronized (DATABASE_LOCK) {
            closeConnection();
            String type = InteractionVisualizer.plugin.getConfiguration().getString("Database.Type");
            isMYSQL = type.equalsIgnoreCase("MYSQL");
            host = InteractionVisualizer.plugin.getConfiguration().getString("Database.MYSQL.Host");
            port = InteractionVisualizer.plugin.getConfiguration().getInt("Database.MYSQL.Port");
            database = InteractionVisualizer.plugin.getConfiguration().getString("Database.MYSQL.Database");
            username = InteractionVisualizer.plugin.getConfiguration().getString("Database.MYSQL.Username");
            password = InteractionVisualizer.plugin.getConfiguration().getString("Database.MYSQL.Password");
            configured = true;

            try {
                Class.forName(isMYSQL ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC");
                ensureConnection();
                createTables(connection);
                log(isMYSQL
                                ? "[InteractionVisualizer] MySQL connected"
                                : "[InteractionVisualizer] Opened SQLite database successfully",
                        NamedTextColor.GREEN);
            } catch (ClassNotFoundException exception) {
                configured = false;
                log("[InteractionVisualizer] Database driver not found", NamedTextColor.RED);
                throw new DatabaseException("Database driver not found", exception);
            } catch (SQLException exception) {
                closeConnection();
                configured = false;
                log("[InteractionVisualizer] Unable to initialize the preference database", NamedTextColor.RED);
                throw new DatabaseException("Unable to initialize the preference database", exception);
            }
        }
    }

    public static Map<Integer, EntryKey> getBitIndex() {
        return executeWithReconnect("load bitmask index", connection -> {
            Map<Integer, EntryKey> index = new HashMap<>();
            try (PreparedStatement statement = prepare(connection,
                    "SELECT ENTRY, BITMASK_INDEX FROM " + INDEX_MAPPING_TABLE);
                 ResultSet results = executeQuery(statement)) {
                while (results.next()) {
                    index.put(results.getInt("BITMASK_INDEX"),
                            new EntryKey(results.getString("ENTRY")));
                }
            }
            return index;
        });
    }

    /** Replaces the complete index atomically without changing its schema. */
    public static void setBitIndex(Map<Integer, EntryKey> index) {
        executeWithReconnect("save bitmask index", connection -> inTransaction(connection, transactional -> {
            try (PreparedStatement delete = prepare(transactional,
                    "DELETE FROM " + INDEX_MAPPING_TABLE)) {
                executeUpdate(delete);
            }
            try (PreparedStatement insert = prepare(transactional,
                    "INSERT INTO " + INDEX_MAPPING_TABLE + " (BITMASK_INDEX,ENTRY) VALUES (?,?)")) {
                for (Map.Entry<Integer, EntryKey> entry : index.entrySet()) {
                    if (entry.getValue() == null) {
                        continue;
                    }
                    insert.setInt(1, entry.getKey());
                    insert.setString(2, entry.getValue().toString());
                    insert.addBatch();
                }
                executeBatch(insert);
            }
            return null;
        }));
    }

    /**
     * Loads an existing player or creates it in the same transaction. The
     * optional initial-disabled mask is written in the INSERT itself, avoiding
     * a follow-up three-column save for first-time players.
     */
    public static Map<Modules, BitSet> loadPlayer(UUID uuid, String name,
                                                   boolean createIfNotFound,
                                                   BitSet initialDisabled) {
        return executeWithReconnect("load player preferences", connection ->
                inTransaction(connection, transactional -> {
                    Map<Modules, BitSet> found = selectPlayer(transactional, uuid);
                    if (found != null || !createIfNotFound) {
                        return found;
                    }

                    BitSet defaults = initialDisabled == null ? new BitSet() : initialDisabled;
                    String encoded = BitSetUtils.toNumberString(defaults);
                    try (PreparedStatement insert = prepare(transactional,
                            "INSERT INTO " + PREFERENCE_TABLE
                                    + " (UUID,NAME,ITEMSTAND,ITEMDROP,HOLOGRAM) VALUES (?,?,?,?,?)")) {
                        insert.setString(1, uuid.toString());
                        insert.setString(2, name == null ? "" : name);
                        insert.setString(3, encoded);
                        insert.setString(4, encoded);
                        insert.setString(5, encoded);
                        executeUpdate(insert);
                    }

                    Map<Modules, BitSet> created = new EnumMap<>(Modules.class);
                    created.put(Modules.ITEMSTAND, (BitSet) defaults.clone());
                    created.put(Modules.ITEMDROP, (BitSet) defaults.clone());
                    created.put(Modules.HOLOGRAM, (BitSet) defaults.clone());
                    return created;
                }));
    }

    /** Saves all three preference modules with one SQL statement. */
    public static void savePlayer(UUID uuid, Map<Modules, BitSet> preferences) {
        if (preferences == null) {
            return;
        }
        executeWithReconnect("save player preferences", connection -> {
            try (PreparedStatement statement = prepare(connection,
                    "UPDATE " + PREFERENCE_TABLE
                            + " SET ITEMSTAND=?, ITEMDROP=?, HOLOGRAM=? WHERE UUID=?")) {
                statement.setString(1, encode(preferences.get(Modules.ITEMSTAND)));
                statement.setString(2, encode(preferences.get(Modules.ITEMDROP)));
                statement.setString(3, encode(preferences.get(Modules.HOLOGRAM)));
                statement.setString(4, uuid.toString());
                executeUpdate(statement);
            }
            return null;
        });
    }

    public static void close() {
        synchronized (DATABASE_LOCK) {
            configured = false;
            closeConnection();
        }
    }

    /** Compatibility hook for older internal callers. */
    public static void runExclusive(Runnable operation) {
        synchronized (DATABASE_LOCK) {
            operation.run();
        }
    }

    public static Connection getConnection() {
        synchronized (DATABASE_LOCK) {
            try {
                ensureConnection();
                return connection;
            } catch (SQLException exception) {
                throw new DatabaseException("Unable to obtain database connection", exception);
            }
        }
    }

    public static void setConnection(Connection replacement) {
        synchronized (DATABASE_LOCK) {
            closeConnection();
            connection = replacement;
            configured = replacement != null;
        }
    }

    public static boolean playerExists(UUID uuid) {
        return executeWithReconnect("check player preferences", connection ->
                selectPlayer(connection, uuid) != null);
    }

    public static void createPlayer(UUID uuid, String name) {
        loadPlayer(uuid, name, true, new BitSet());
    }

    public static Map<Modules, BitSet> getPlayerInfo(UUID uuid) {
        Map<Modules, BitSet> preferences = loadPlayer(uuid, "", false, null);
        return preferences == null ? new EnumMap<>(Modules.class) : preferences;
    }

    public static void setItemStand(UUID uuid, BitSet bitset) {
        setSingleModule(uuid, "ITEMSTAND", bitset);
    }

    public static void setItemDrop(UUID uuid, BitSet bitset) {
        setSingleModule(uuid, "ITEMDROP", bitset);
    }

    public static void setHologram(UUID uuid, BitSet bitset) {
        setSingleModule(uuid, "HOLOGRAM", bitset);
    }

    private static void setSingleModule(UUID uuid, String column, BitSet bitset) {
        executeWithReconnect("save player preference module", connection -> {
            try (PreparedStatement statement = prepare(connection,
                    "UPDATE " + PREFERENCE_TABLE + " SET " + column + "=? WHERE UUID=?")) {
                statement.setString(1, encode(bitset));
                statement.setString(2, uuid.toString());
                executeUpdate(statement);
            }
            return null;
        });
    }

    private static Map<Modules, BitSet> selectPlayer(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = prepare(connection,
                "SELECT ITEMSTAND, ITEMDROP, HOLOGRAM FROM " + PREFERENCE_TABLE + " WHERE UUID=?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet results = executeQuery(statement)) {
                if (!results.next()) {
                    return null;
                }
                Map<Modules, BitSet> preferences = new EnumMap<>(Modules.class);
                preferences.put(Modules.ITEMSTAND, decode(results.getString("ITEMSTAND"), uuid));
                preferences.put(Modules.ITEMDROP, decode(results.getString("ITEMDROP"), uuid));
                preferences.put(Modules.HOLOGRAM, decode(results.getString("HOLOGRAM"), uuid));
                return preferences;
            }
        }
    }

    private static BitSet decode(String encoded, UUID uuid) {
        if (encoded == null || encoded.isEmpty()) {
            return new BitSet();
        }
        try {
            if (VALID_BITSET.matcher(encoded).matches()) {
                return BitSetUtils.fromNumberString(encoded);
            }
            return BitSet.valueOf(ArrayUtils.fromBase64String(encoded));
        } catch (Throwable throwable) {
            InteractionVisualizer.plugin.getLogger().warning(
                    "Unable to decode player preference for " + uuid + "; using enabled defaults");
            return new BitSet();
        }
    }

    private static String encode(BitSet bitset) {
        return BitSetUtils.toNumberString(bitset == null ? new BitSet() : bitset);
    }

    private static void createTables(Connection connection) throws SQLException {
        String preferenceDefinition = isMYSQL
                ? " (UUID Text, NAME Text, ITEMSTAND Text, ITEMDROP Text, HOLOGRAM Text)"
                : " (UUID TEXT PRIMARY KEY, NAME TEXT NOT NULL, ITEMSTAND TEXT, ITEMDROP TEXT, HOLOGRAM TEXT)";
        String indexDefinition = isMYSQL
                ? " (ENTRY Text, BITMASK_INDEX INT)"
                : " (ENTRY TEXT PRIMARY KEY, BITMASK_INDEX INTEGER)";
        try (Statement statement = connection.createStatement()) {
            configure(statement);
            PerformanceMetrics.preferenceSqlStatement();
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + PREFERENCE_TABLE
                    + preferenceDefinition);
        }
        try (Statement statement = connection.createStatement()) {
            configure(statement);
            PerformanceMetrics.preferenceSqlStatement();
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + INDEX_MAPPING_TABLE
                    + indexDefinition);
        }
    }

    private static void ensureConnection() throws SQLException {
        if (!configured) {
            throw new SQLException("Preference database is closed");
        }
        if (connection != null && !connection.isClosed()) {
            return;
        }
        if (isMYSQL) {
            Properties properties = new Properties();
            properties.setProperty("user", username);
            properties.setProperty("password", password);
            properties.setProperty("connectTimeout", Integer.toString(CONNECT_TIMEOUT_MILLIS));
            properties.setProperty("socketTimeout", Integer.toString(SOCKET_TIMEOUT_MILLIS));
            properties.setProperty("tcpKeepAlive", "true");
            connection = DriverManager.getConnection(
                    "jdbc:mysql://" + host + ":" + port + "/" + database, properties);
        } else {
            connection = DriverManager.getConnection(
                    "jdbc:sqlite:plugins/InteractionVisualizer/database.db");
            try (Statement statement = connection.createStatement()) {
                PerformanceMetrics.preferenceSqlStatement();
                statement.execute("PRAGMA busy_timeout=" + CONNECT_TIMEOUT_MILLIS);
            }
        }
    }

    private static <T> T executeWithReconnect(String description, CheckedOperation<Connection, T> operation) {
        synchronized (DATABASE_LOCK) {
            return retryOnce(description,
                    () -> {
                        ensureConnection();
                        return connection;
                    }, operation, Database::closeConnection,
                    PerformanceMetrics::preferenceDatabaseReconnect);
        }
    }

    static <C, T> T retryOnce(String description, CheckedSupplier<C> connectionSupplier,
                              CheckedOperation<C, T> operation, Runnable discardConnection,
                              Runnable reconnectMetric) {
        SQLException firstFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return operation.execute(connectionSupplier.get());
            } catch (SQLException exception) {
                discardConnection.run();
                if (firstFailure == null) {
                    firstFailure = exception;
                    reconnectMetric.run();
                } else {
                    exception.addSuppressed(firstFailure);
                    throw new DatabaseException("Unable to " + description, exception);
                }
            }
        }
        throw new DatabaseException("Unable to " + description, firstFailure);
    }

    private static <T> T inTransaction(Connection connection,
                                        CheckedOperation<Connection, T> operation) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = operation.execute(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        } finally {
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException ignored) {
                // The operation committed, but this connection is no longer safe to reuse.
                closeConnection();
            }
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        configure(statement);
        return statement;
    }

    private static void configure(Statement statement) throws SQLException {
        try {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        } catch (SQLFeatureNotSupportedException | AbstractMethodError ignored) {
            // The MySQL socket timeout and SQLite busy timeout remain in force.
        }
    }

    private static ResultSet executeQuery(PreparedStatement statement) throws SQLException {
        PerformanceMetrics.preferenceSqlStatement();
        return statement.executeQuery();
    }

    private static int executeUpdate(PreparedStatement statement) throws SQLException {
        PerformanceMetrics.preferenceSqlStatement();
        return statement.executeUpdate();
    }

    private static int[] executeBatch(PreparedStatement statement) throws SQLException {
        PerformanceMetrics.preferenceSqlStatement();
        return statement.executeBatch();
    }

    private static void closeConnection() {
        Connection current = connection;
        connection = null;
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (SQLException ignored) {
        }
    }

    private static void log(String message, NamedTextColor color) {
        Bukkit.getConsoleSender().sendMessage(Component.text(message, color));
    }

    @FunctionalInterface
    interface CheckedSupplier<C> {

        C get() throws SQLException;
    }

    @FunctionalInterface
    interface CheckedOperation<C, T> {

        T execute(C connection) throws SQLException;
    }

    public static final class DatabaseException extends RuntimeException {

        public DatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
