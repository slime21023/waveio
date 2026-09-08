package io.waveio.server;

/** Explicit finite resource limits for one WaveIO HTTP server. */
public record ServerLimits(int maximumInitialLineLength, int maximumHeaderSize, int maximumChunkSize) {
    /** Validates all transport resource limits. */
    public ServerLimits {
        if (maximumInitialLineLength < 1 || maximumHeaderSize < 1 || maximumChunkSize < 1) {
            throw new IllegalArgumentException("server limits must be positive");
        }
    }
}
