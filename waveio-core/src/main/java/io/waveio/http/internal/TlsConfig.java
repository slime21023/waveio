package io.waveio.http.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public record TlsConfig(Path certificateChain, Path privateKey) {
    public TlsConfig {
        certificateChain = readableFile(certificateChain, "certificateChain");
        privateKey = readableFile(privateKey, "privateKey");
    }

    private static Path readableFile(Path value, String name) {
        Objects.requireNonNull(value, name);
        var normalized = value.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || !Files.isReadable(normalized)) {
            throw new IllegalArgumentException(name + " must be a readable regular file: "
                    + normalized);
        }
        return normalized;
    }
}
