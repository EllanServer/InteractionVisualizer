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
import com.loohp.interactionvisualizer.scheduler.Scheduler;
import org.bukkit.Bukkit;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AsyncExecutorManager implements AutoCloseable {

    private final ExecutorService executor;
    private final AtomicBoolean valid;

    public AsyncExecutorManager(ExecutorService executor) {
        this.executor = executor;
        this.valid = new AtomicBoolean(true);
    }

    public void runTaskAsynchronously(Runnable runnable) {
        if (!valid.get()) {
            return;
        }
        try {
            executor.submit(runnable);
        } catch (RejectedExecutionException ignored) {
            // The plugin began shutting down between the validity check and submit.
        }
    }

    public void runTaskLaterAsynchronously(Runnable runnable, long delay) {
        if (!valid.get()) {
            return;
        }
        Scheduler.runTaskLater(InteractionVisualizer.plugin, () -> runTaskAsynchronously(runnable), delay);
    }

    public void runTaskSynchronously(Runnable runnable) {
        if (valid.get()) {
            Scheduler.executeOrScheduleSync(InteractionVisualizer.plugin, runnable);
        }
    }

    public boolean isValid() {
        return valid.get();
    }

    @Override
    public void close() {
        if (!valid.compareAndSet(true, false)) {
            return;
        }
        executor.shutdown();
        boolean interrupted = false;
        try {
            if (!executor.awaitTermination(2L, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(2L, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            executor.shutdownNow();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public int retainedTaskCount() {
        if (executor instanceof ThreadPoolExecutor threadPool) {
            return threadPool.getActiveCount() + threadPool.getQueue().size();
        }
        return executor.isTerminated() ? 0 : 1;
    }

}
