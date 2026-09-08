package io.waveio.server;

import io.waveio.http.HttpResponse;

/** Renders exactly one declared runtime class to an HTTP response. */
public interface Renderer<T> {
    /** Returns the exact class this renderer accepts. */
    Class<T> type();
    /** Renders a non-null value of the declared type. */
    HttpResponse render(T value);
}
