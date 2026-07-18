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

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseRetryTest {

    @Test
    void discardsAndRetriesOnceAfterAConnectionFailure() {
        AtomicInteger connections = new AtomicInteger();
        AtomicInteger discards = new AtomicInteger();
        AtomicInteger reconnects = new AtomicInteger();

        String result = Database.retryOnce("test operation",
                connections::incrementAndGet,
                connection -> {
                    if (connection == 1) {
                        throw new SQLException("connection lost");
                    }
                    return "ok-" + connection;
                }, discards::incrementAndGet, reconnects::incrementAndGet);

        assertEquals("ok-2", result);
        assertEquals(2, connections.get());
        assertEquals(1, discards.get());
        assertEquals(1, reconnects.get());
    }

    @Test
    void secondSqlFailureIsPropagatedWithTheFirstFailureAttached() {
        SQLException first = new SQLException("first");
        SQLException second = new SQLException("second");
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger discards = new AtomicInteger();
        AtomicInteger reconnects = new AtomicInteger();

        Database.DatabaseException failure = assertThrows(Database.DatabaseException.class,
                () -> Database.retryOnce("test operation", attempts::incrementAndGet,
                        connection -> {
                            throw connection == 1 ? first : second;
                        }, discards::incrementAndGet, reconnects::incrementAndGet));

        assertSame(second, failure.getCause());
        assertEquals(1, second.getSuppressed().length);
        assertSame(first, second.getSuppressed()[0]);
        assertEquals(2, discards.get());
        assertEquals(1, reconnects.get());
    }

    @Test
    void applicationFailuresAreNotRetriedAsConnectionFailures() {
        AtomicInteger connections = new AtomicInteger();
        AtomicInteger discards = new AtomicInteger();
        AtomicInteger reconnects = new AtomicInteger();
        IllegalStateException applicationFailure = new IllegalStateException("bad data");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> Database.retryOnce("test operation", connections::incrementAndGet,
                        connection -> {
                            throw applicationFailure;
                        }, discards::incrementAndGet, reconnects::incrementAndGet));

        assertSame(applicationFailure, thrown);
        assertEquals(1, connections.get());
        assertEquals(0, discards.get());
        assertEquals(0, reconnects.get());
    }
}
