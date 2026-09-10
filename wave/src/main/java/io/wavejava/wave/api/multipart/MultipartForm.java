package io.wavejava.wave.api.multipart;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable multipart part metadata with ownership of any temporary uploads.
 *
 * <p>Call {@link #close()} after application processing to delete all temporary upload files.
 * Closing the form is idempotent.</p>
 */
public final class MultipartForm implements AutoCloseable {
    private final List<MultipartPart> parts;
    private final Map<String, List<MultipartPart>> partsByName;
    private final TemporaryFileManager temporaryFiles;

    MultipartForm(List<MultipartPart> parts, TemporaryFileManager temporaryFiles) {
        this.parts = List.copyOf(parts);
        this.temporaryFiles = Objects.requireNonNull(temporaryFiles, "temporaryFiles");
        var grouped = new LinkedHashMap<String, List<MultipartPart>>();
        for (var part : parts) {
            grouped.computeIfAbsent(part.name(), ignored -> new ArrayList<>()).add(part);
        }
        var immutable = new LinkedHashMap<String, List<MultipartPart>>();
        grouped.forEach((name, namedParts) -> immutable.put(name, List.copyOf(namedParts)));
        partsByName = Collections.unmodifiableMap(immutable);
    }

    /** Returns parts in wire order. */
    public List<MultipartPart> parts() {
        return parts;
    }

    /** Returns all parts for one field name in wire order. */
    public List<MultipartPart> parts(String name) {
        return partsByName.getOrDefault(Objects.requireNonNull(name, "name"), List.of());
    }

    /** Returns the first part for one field name, if present. */
    public Optional<MultipartPart> first(String name) {
        var namedParts = parts(name);
        return namedParts.isEmpty() ? Optional.empty() : Optional.of(namedParts.getFirst());
    }

    /** Returns whether the form's temporary upload owner is already closed. */
    public boolean isClosed() {
        return temporaryFiles.isClosed();
    }

    /** Deletes every temporary upload file created for this form. */
    @Override
    public void close() throws IOException {
        temporaryFiles.close();
    }
}
