package io.wavejava.wave.api.http;

import java.util.Locale;
import java.util.Objects;

/** HTTP protocol versions which may be represented by a {@link Request}. */
public enum HttpVersion {
    HTTP_1_0("HTTP/1.0"),
    HTTP_1_1("HTTP/1.1"),
    HTTP_2("HTTP/2");

    private final String wireName;

    HttpVersion(String wireName) {
        this.wireName = wireName;
    }

    /** Returns the protocol name as it appears on the wire. */
    public String wireName() {
        return wireName;
    }

    /** Parses a supported HTTP version name, ignoring ASCII case. */
    public static HttpVersion of(String wireName) {
        Objects.requireNonNull(wireName, "wireName");
        var normalized = wireName.toUpperCase(Locale.ROOT);
        for (var version : values()) {
            if (version.wireName.equals(normalized)) {
                return version;
            }
        }
        throw new IllegalArgumentException("Unsupported HTTP version: " + wireName);
    }

    @Override
    public String toString() {
        return wireName;
    }
}
