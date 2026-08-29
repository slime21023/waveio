package io.waveio.http.body;

import io.waveio.http.internal.body.FileBodyPublisher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.Flow;

/**
 * Sealed hierarchy representing an HTTP response payload.
 *
 * <p>WaveIO supports three distinct forms of response bodies:
 * <ul>
 *   <li>{@link Bytes}: In-memory raw byte array with known content length.</li>
 *   <li>{@link Value}: Generic application value awaiting serialization via {@link BodyCodec}.</li>
 *   <li>{@link Stream}: Reactive stream of {@link ByteBuffer} instances powered by {@link Flow.Publisher}.</li>
 * </ul>
 */
public sealed interface ResponseBody
        permits ResponseBody.Bytes, ResponseBody.Stream, ResponseBody.Value {

    /**
     * Returns the declared content length in bytes, if statically known.
     *
     * @return the optional content length in bytes
     */
    OptionalLong contentLength();

    /**
     * Creates an empty response body with zero bytes.
     *
     * @return an empty {@link Bytes} response body
     */
    static Bytes empty() { return new Bytes(new byte[0]); }

    /**
     * Creates an in-memory byte response body.
     *
     * @param value the raw byte payload
     * @return a {@link Bytes} response body
     */
    static Bytes bytes(byte[] value) { return new Bytes(value); }

    /**
     * Creates a chunked streaming response body without a predefined content length.
     *
     * @param publisher reactive publisher emitting byte buffers
     * @return a {@link Stream} response body
     */
    static Stream stream(Flow.Publisher<ByteBuffer> publisher) {
        return new Stream(publisher, OptionalLong.empty());
    }

    /**
     * Creates a fixed-length streaming response body.
     *
     * @param publisher reactive publisher emitting byte buffers
     * @param contentLength total length of the stream in bytes
     * @return a {@link Stream} response body
     * @throws IllegalArgumentException if {@code contentLength} is negative
     */
    static Stream stream(Flow.Publisher<ByteBuffer> publisher, long contentLength) {
        if (contentLength < 0) throw new IllegalArgumentException("contentLength must not be negative");
        return new Stream(publisher, OptionalLong.of(contentLength));
    }

    /**
     * An application value awaiting encoding by the server's {@link BodyCodec}.
     *
     * <p>Encoding is deferred to the transport because a handler builds its response without
     * access to server configuration; the writer converts this to {@link Bytes} before any
     * framing decision, so HEAD and body-forbidden statuses behave exactly as for byte bodies.
     *
     * @param value the application value to encode
     * @return a {@link Value} response body
     */
    static Value value(Object value) { return new Value(value); }

    /**
     * Creates a streaming response body that reads a file asynchronously on virtual threads.
     *
     * @param path the filesystem path of the file to serve
     * @return a {@link Stream} response body with known file size
     * @throws UncheckedIOException if file metadata cannot be inspected
     */
    static Stream file(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            long length = java.nio.file.Files.size(path);
            return new Stream(new FileBodyPublisher(path, 16 * 1024), OptionalLong.of(length));
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot inspect response file: " + path, failure);
        }
    }

    /**
     * An in-memory byte array response payload.
     */
    final class Bytes implements ResponseBody {
        private final byte[] value;
        private Bytes(byte[] value) {
            this.value = Arrays.copyOf(Objects.requireNonNull(value, "value"), value.length);
        }

        /**
         * Returns a defensive copy of the raw body bytes.
         *
         * @return byte array payload
         */
        public byte[] value() { return Arrays.copyOf(value, value.length); }

        @Override public OptionalLong contentLength() { return OptionalLong.of(value.length); }
    }

    /**
     * An unencoded application value object.
     *
     * @param value the value object to be serialized by {@link BodyCodec}
     */
    record Value(Object value) implements ResponseBody {
        public Value {
            Objects.requireNonNull(value, "value");
        }
        /** Unknown until the codec has encoded it; the writer never consults this. */
        @Override public OptionalLong contentLength() { return OptionalLong.empty(); }
    }

    /**
     * A reactive streaming response payload.
     *
     * @param publisher reactive publisher of byte buffers
     * @param contentLength optional known content length in bytes
     */
    record Stream(Flow.Publisher<ByteBuffer> publisher, OptionalLong contentLength)
            implements ResponseBody {
        public Stream {
            Objects.requireNonNull(publisher, "publisher");
            Objects.requireNonNull(contentLength, "contentLength");
            if (contentLength.isPresent() && contentLength.getAsLong() < 0) {
                throw new IllegalArgumentException("contentLength must not be negative");
            }
        }
    }
}
