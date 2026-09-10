package io.wavejava.wave.api.config;

import java.util.Objects;

/** Internal validation and joining rules for dotted configuration paths. */
final class ConfigPath {
    private ConfigPath() {
    }

    static String requirePath(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isBlank()) {
            throw new IllegalArgumentException("Configuration path must not be blank");
        }
        if (path.startsWith(".") || path.endsWith(".") || path.contains("..")) {
            throw new IllegalArgumentException("Configuration path must use non-empty dot-separated segments: " + path);
        }
        for (var segment : path.split("\\.")) {
            if (segment.isBlank()) {
                throw new IllegalArgumentException("Configuration path contains a blank segment: " + path);
            }
        }
        return path;
    }

    static String requirePrefix(String prefix) {
        Objects.requireNonNull(prefix, "path");
        if (prefix.isEmpty()) {
            return "";
        }
        return requirePath(prefix);
    }

    static String child(String prefix, String segment) {
        requirePrefix(prefix);
        Objects.requireNonNull(segment, "segment");
        if (segment.isBlank() || segment.indexOf('.') >= 0) {
            throw new IllegalArgumentException("Configuration record component must be one non-blank path segment: " + segment);
        }
        return prefix.isEmpty() ? segment : prefix + '.' + segment;
    }
}
