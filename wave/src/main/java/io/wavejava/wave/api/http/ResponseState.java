package io.wavejava.wave.api.http;

/** The lifecycle state of a response belonging to a single request invocation. */
public enum ResponseState {
    /** Headers and body may still be changed. */
    OPEN,
    /** The response is fixed and may be handed to the transport. */
    COMMITTED,
    /** The transport reported a successful write completion. */
    COMPLETED,
    /** The response can no longer be written, for example after a client disconnect. */
    ABORTED
}
