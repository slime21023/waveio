package io.wavejava.wave.api.multipart;

import io.wavejava.wave.api.http.MediaType;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Owns a per-form temporary directory and supports bounded incremental file writes. */
final class TemporaryFileManager implements AutoCloseable {
    private final Path baseDirectory;
    private final long maximumDiskBytes;
    private final Set<Path> files = new LinkedHashSet<>();
    private final Set<Upload> uploads = new LinkedHashSet<>();
    private final Set<PendingUpload> pendingUploads = new LinkedHashSet<>();

    private Path sessionDirectory;
    private long diskBytes;
    private boolean closed;

    TemporaryFileManager(Path baseDirectory, long maximumDiskBytes) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory").toAbsolutePath().normalize();
        this.maximumDiskBytes = maximumDiskBytes;
    }

    synchronized PendingUpload beginUpload(String filename, MediaType mediaType) throws IOException {
        ensureOpen();
        var directory = sessionDirectory();
        var path = Files.createTempFile(directory, "part-", ".upload");
        var pending = new PendingUpload(path, Upload.normalizeFilename(filename), mediaType,
                Files.newOutputStream(path, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
        files.add(path);
        pendingUploads.add(pending);
        return pending;
    }

    synchronized void write(PendingUpload upload, byte[] content, int offset, int length) throws IOException {
        ensureOpen();
        Objects.requireNonNull(upload, "upload");
        Objects.requireNonNull(content, "content");
        if (!pendingUploads.contains(upload)) {
            throw new IllegalStateException("Temporary upload is not active");
        }
        if (offset < 0 || length < 0 || offset > content.length - length) {
            throw new IndexOutOfBoundsException("Invalid temporary upload byte range");
        }
        if (length > maximumDiskBytes - diskBytes) {
            throw new MultipartLimitExceededException(MultipartLimitExceededException.Limit.TEMPORARY_DISK_BYTES,
                    diskBytes + length, maximumDiskBytes);
        }
        upload.output.write(content, offset, length);
        upload.size += length;
        diskBytes += length;
    }

    synchronized Upload finish(PendingUpload upload) throws IOException {
        ensureOpen();
        Objects.requireNonNull(upload, "upload");
        if (!pendingUploads.remove(upload)) {
            throw new IllegalStateException("Temporary upload is not active");
        }
        upload.closeOutput();
        var result = new Upload(this, upload.path, upload.filename, upload.mediaType, upload.size);
        uploads.add(result);
        return result;
    }

    synchronized boolean isClosed() {
        return closed;
    }

    synchronized void release(Upload upload) throws IOException {
        Objects.requireNonNull(upload, "upload");
        if (!upload.markClosed()) {
            return;
        }
        uploads.remove(upload);
        deleteManagedPath(upload.managedPath());
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        for (var pending : new ArrayList<>(pendingUploads)) {
            try {
                pending.closeOutput();
            } catch (IOException closeFailure) {
                failure = append(failure, closeFailure);
            }
        }
        pendingUploads.clear();
        for (var upload : uploads) {
            upload.markClosed();
        }
        uploads.clear();

        for (var path : new ArrayList<>(files)) {
            try {
                deleteManagedPath(path);
            } catch (IOException cleanupFailure) {
                failure = append(failure, cleanupFailure);
            }
        }
        if (sessionDirectory != null) {
            try {
                Files.deleteIfExists(sessionDirectory);
            } catch (IOException cleanupFailure) {
                failure = append(failure, cleanupFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private Path sessionDirectory() throws IOException {
        if (sessionDirectory == null) {
            Files.createDirectories(baseDirectory);
            sessionDirectory = Files.createTempDirectory(baseDirectory, "wave-multipart-");
        }
        return sessionDirectory;
    }

    private void deleteManagedPath(Path path) throws IOException {
        Files.deleteIfExists(path);
        files.remove(path);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Temporary upload manager is already closed");
        }
    }

    private static IOException append(IOException current, IOException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    /** An internal file part that remains writable until its boundary is reached. */
    static final class PendingUpload {
        private final Path path;
        private final String filename;
        private final MediaType mediaType;
        private OutputStream output;
        private long size;

        private PendingUpload(Path path, String filename, MediaType mediaType, OutputStream output) {
            this.path = path;
            this.filename = filename;
            this.mediaType = mediaType;
            this.output = output;
        }

        private void closeOutput() throws IOException {
            if (output != null) {
                try {
                    output.close();
                } finally {
                    output = null;
                }
            }
        }
    }
}
