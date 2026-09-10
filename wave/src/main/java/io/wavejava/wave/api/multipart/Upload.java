package io.wavejava.wave.api.multipart;

import io.wavejava.wave.api.http.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A file part materialized into a parser-owned temporary file.
 *
 * <p>The temporary path is valid until this upload or its owning {@link MultipartForm} is closed.
 * Applications that need to retain the data must copy or move it into their own managed storage
 * before closing the form.</p>
 */
/** One file part and its bounded temporary-file lifecycle from a {@code MultipartForm}. */
public final class Upload implements AutoCloseable {
    private final TemporaryFileManager manager;
    private final Path path;
    private final String filename;
    private final MediaType mediaType;
    private final long size;
    private final AtomicBoolean closed = new AtomicBoolean();

    Upload(TemporaryFileManager manager, Path path, String filename, MediaType mediaType, long size) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.path = Objects.requireNonNull(path, "path");
        this.filename = normalizeFilename(filename);
        this.mediaType = mediaType;
        this.size = size;
    }

    /**
     * Returns the normalized final filename component supplied by the client.
     *
     * <p>Directory components are removed and the value must never be used as a destination path
     * without an application-owned storage policy.</p>
     */
    public String filename() {
        return filename;
    }

    /** Returns the media type declared by the file part, if any. */
    public Optional<MediaType> mediaType() {
        return Optional.ofNullable(mediaType);
    }

    /** Returns the uploaded content size in bytes. */
    public long size() {
        return size;
    }

    /**
     * Returns the parser-owned temporary path while this upload remains open.
     *
     * @throws IllegalStateException if the upload or owning form has already been closed
     */
    public Path path() {
        ensureOpen();
        return path;
    }

    /** Opens a new read stream for the temporary content. */
    public InputStream openStream() throws IOException {
        ensureOpen();
        return Files.newInputStream(path);
    }

    /** Returns whether this upload has not yet been cleaned up. */
    public boolean isOpen() {
        return !closed.get() && !manager.isClosed();
    }

    /** Deletes this temporary upload. Repeated calls are harmless. */
    @Override
    public void close() throws IOException {
        manager.release(this);
    }

    boolean markClosed() {
        return closed.compareAndSet(false, true);
    }

    Path managedPath() {
        return path;
    }

    private void ensureOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("Upload has already been closed: " + filename);
        }
    }

    static String normalizeFilename(String candidate) {
        Objects.requireNonNull(candidate, "filename");
        if (candidate.indexOf('\u0000') >= 0 || candidate.indexOf('\r') >= 0 || candidate.indexOf('\n') >= 0) {
            throw new MultipartParseException("Multipart filename contains an unsafe character");
        }
        var normalized = candidate.replace('\\', '/');
        var slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        if (normalized.isBlank() || normalized.equals(".") || normalized.equals("..")) {
            return "upload";
        }
        return normalized;
    }
}
