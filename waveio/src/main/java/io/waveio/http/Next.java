package io.waveio.http;

import io.waveio.task.Task;

/** Continues a high-level endpoint middleware pipeline. */
@FunctionalInterface
public interface Next {
    /** Invokes the remaining endpoint pipeline. */
    Task<HttpResponse> proceed();
}
