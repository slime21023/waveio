package io.waveio.http;

import io.waveio.task.Task;

/** Handles one HTTP context and returns its managed completion task. */
@FunctionalInterface
public interface Handler { Task<Void> handle(Context context); }
