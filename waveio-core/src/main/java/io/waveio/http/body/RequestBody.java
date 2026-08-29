package io.waveio.http.body;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Immutable byte container representing an in-memory HTTP request payload.
 *
 * <p>Instances of this class are immutable and safe for concurrent access.
 */
public final class RequestBody {
    private final byte[] bytes;

    private RequestBody(byte[] bytes) { this.bytes = bytes; }

    /**
     * Creates a request body wrapping a defensive copy of the given byte array.
     *
     * @param bytes the raw request body bytes
     * @return an immutable {@code RequestBody} instance
     */
    public static RequestBody of(byte[] bytes) {
        java.util.Objects.requireNonNull(bytes, "bytes");
        return new RequestBody(Arrays.copyOf(bytes, bytes.length));
    }

    /**
     * Returns an empty request body instance.
     *
     * @return an empty {@code RequestBody}
     */
    public static RequestBody empty() { return new RequestBody(new byte[0]); }

    /**
     * Returns the size of the request body in bytes.
     *
     * @return the number of bytes in this body
     */
    public int size() { return bytes.length; }

    /**
     * Returns whether this request body is empty.
     *
     * @return {@code true} if this body contains zero bytes, {@code false} otherwise
     */
    public boolean isEmpty() { return bytes.length == 0; }

    /**
     * Returns a defensive copy of the raw request body bytes.
     *
     * @return a new byte array containing the body data
     */
    public byte[] bytes() { return Arrays.copyOf(bytes, bytes.length); }

    /**
     * Decodes the request body bytes as a UTF-8 string.
     *
     * @return the body decoded as a UTF-8 string
     */
    public String text() { return text(StandardCharsets.UTF_8); }

    /**
     * Decodes the request body bytes using the specified character set.
     *
     * @param charset the character encoding to use for decoding
     * @return the decoded string
     */
    public String text(Charset charset) {
        java.util.Objects.requireNonNull(charset, "charset");
        return new String(bytes, charset);
    }
}
