package io.wavejava.wave.api.file;

import io.wavejava.wave.api.http.MediaType;
import io.wavejava.wave.api.http.Problem;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * A bounded, conditional file representation for one response.
 *
 * <p>0.3 intentionally reads only a finite byte range into framework-owned storage. It does not
 * expose file streaming or multipart ranges; those require the 0.4 Flow transport boundary. The
 * maximum applies to bytes selected for this response, so a client may safely request a small
 * range of a larger file without causing a whole-file allocation.</p>
 */
public final class FileResponse {
    /** Default maximum number of file bytes materialized for one aggregated response. */
    public static final int DEFAULT_MAXIMUM_BYTES = 8 * 1024 * 1024;

    private final Path file;
    private final MediaType mediaType;
    private final int maximumBytes;

    private FileResponse(Builder builder) {
        file = Objects.requireNonNull(builder.file, "file");
        mediaType = builder.mediaType == null ? detectMediaType(file) : builder.mediaType;
        maximumBytes = builder.maximumBytes;
    }

    /** Returns a bounded file response with the documented default byte limit. */
    public static FileResponse of(Path file) {
        return builder(file).build();
    }

    /** Starts configuration of one file response. */
    public static Builder builder(Path file) {
        return new Builder(file);
    }

    /** Returns the selected source path. */
    public Path file() {
        return file;
    }

    /** Returns the fixed response media type. */
    public MediaType mediaType() {
        return mediaType;
    }

    /** Returns the maximum materialized response bytes. */
    public int maximumBytes() {
        return maximumBytes;
    }

    /**
     * Applies cache validators, an optional single byte range, and the bounded file bytes.
     *
     * @throws IOException when the selected source cannot be read consistently
     */
    public void writeTo(Request request, Response response) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(file.toString());
        }

        var resourceLength = Files.size(file);
        var lastModified = Files.getLastModifiedTime(file).toInstant();
        var etag = etag(resourceLength, lastModified);
        response.header("ETag", etag)
                .header("Last-Modified", ConditionalRequestEvaluator.formatHttpDate(lastModified))
                .header("Accept-Ranges", "bytes");

        if (ConditionalRequestEvaluator.evaluate(request, etag, lastModified)
                == ConditionalRequestEvaluator.Result.NOT_MODIFIED) {
            response.status(304);
            return;
        }

        var start = 0L;
        var length = resourceLength;
        var partial = false;
        var rangeHeader = request.header("Range");
        if (rangeHeader.isPresent()) {
            final Range range;
            try {
                range = Range.parse(rangeHeader.orElseThrow());
            } catch (IllegalArgumentException invalid) {
                writeUnsatisfiableRange(response, resourceLength);
                return;
            }
            var resolved = range.resolve(resourceLength);
            if (resolved.isEmpty()) {
                writeUnsatisfiableRange(response, resourceLength);
                return;
            }
            var interval = resolved.orElseThrow();
            start = interval.start();
            length = interval.length();
            partial = true;
            response.header("Content-Range", "bytes " + interval.start() + '-' + interval.endInclusive() + '/' + resourceLength);
        }

        if (length > maximumBytes) {
            response.problem(Problem.of(413, "Payload Too Large"));
            return;
        }

        var bytes = readSegment(file, start, Math.toIntExact(length));
        if (partial) {
            response.status(206);
        }
        response.bytes(bytes, mediaType);
    }

    private static void writeUnsatisfiableRange(Response response, long resourceLength) {
        response.header("Content-Range", "bytes */" + resourceLength)
                .problem(Problem.of(416, "Range Not Satisfiable"));
    }

    private static byte[] readSegment(Path file, long start, int length) throws IOException {
        var bytes = new byte[length];
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(start);
            var target = ByteBuffer.wrap(bytes);
            while (target.hasRemaining()) {
                if (channel.read(target) < 0) {
                    throw new EOFException("File changed while reading selected range: " + file);
                }
            }
        }
        return bytes;
    }

    private static String etag(long resourceLength, Instant lastModified) {
        return '"' + Long.toUnsignedString(resourceLength, 16) + '-' + Long.toUnsignedString(lastModified.toEpochMilli(), 16) + '"';
    }

    private static MediaType detectMediaType(Path file) {
        try {
            var detected = Files.probeContentType(file);
            if (detected != null) {
                return MediaType.parse(detected);
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // A platform MIME registry is optional; use a predictable fallback below.
        }
        var name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            return MediaType.of("text", "html").withCharset(java.nio.charset.StandardCharsets.UTF_8);
        }
        if (name.endsWith(".css")) {
            return MediaType.of("text", "css").withCharset(java.nio.charset.StandardCharsets.UTF_8);
        }
        if (name.endsWith(".js") || name.endsWith(".mjs")) {
            return MediaType.of("text", "javascript").withCharset(java.nio.charset.StandardCharsets.UTF_8);
        }
        if (name.endsWith(".json")) {
            return MediaType.APPLICATION_JSON;
        }
        if (name.endsWith(".txt")) {
            return MediaType.TEXT_PLAIN_UTF_8;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /** Builder for a bounded {@link FileResponse}. */
    public static final class Builder {
        private final Path file;
        private MediaType mediaType;
        private int maximumBytes = DEFAULT_MAXIMUM_BYTES;

        private Builder(Path file) {
            this.file = Objects.requireNonNull(file, "file");
        }

        /** Overrides platform media-type detection. */
        public Builder mediaType(MediaType mediaType) {
            this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
            return this;
        }

        /** Sets the maximum number of bytes materialized for the selected representation. */
        public Builder maximumBytes(long maximumBytes) {
            if (maximumBytes <= 0 || maximumBytes > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("maximumBytes must be between 1 and " + Integer.MAX_VALUE);
            }
            this.maximumBytes = (int) maximumBytes;
            return this;
        }

        /** Creates an immutable file response. */
        public FileResponse build() {
            return new FileResponse(this);
        }
    }
}
