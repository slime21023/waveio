package io.waveio.registry;

/** Thrown when a requested registry entry is absent. */
public final class MissingRegistryEntryException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates an exception for the missing key. */
    public MissingRegistryEntryException(Key<?> key) {
        super("No registry entry for " + key);
    }
}
