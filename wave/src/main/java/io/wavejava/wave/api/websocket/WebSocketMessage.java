package io.wavejava.wave.api.websocket;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Immutable, Netty-free WebSocket application or control message. */
public final class WebSocketMessage {
    /** Wire-level message kinds accepted for outbound session operations. */
    public enum Type {
        TEXT,
        BINARY,
        PING,
        PONG,
        CLOSE
    }

    private final Type type;
    private final byte[] payload;
    private final int closeCode;

    private WebSocketMessage(Type type, byte[] payload, int closeCode) {
        this.type = Objects.requireNonNull(type, "type");
        this.payload = Objects.requireNonNull(payload, "payload").clone();
        this.closeCode = closeCode;
    }

    /** Creates a UTF-8 text message. */
    public static WebSocketMessage text(String text) {
        return new WebSocketMessage(Type.TEXT, Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8), 0);
    }

    /** Creates a binary message from a defensive byte copy. */
    public static WebSocketMessage binary(byte[] bytes) {
        return new WebSocketMessage(Type.BINARY, Objects.requireNonNull(bytes, "bytes"), 0);
    }

    /** Creates a binary message from the remaining bytes in {@code bytes}. */
    public static WebSocketMessage binary(ByteBuffer bytes) {
        Objects.requireNonNull(bytes, "bytes");
        var copy = new byte[bytes.remaining()];
        bytes.slice().get(copy);
        return binary(copy);
    }

    /** Creates a ping control message with at most 125 payload bytes. */
    public static WebSocketMessage ping(byte[] bytes) {
        return control(Type.PING, bytes);
    }

    /** Creates a pong control message with at most 125 payload bytes. */
    public static WebSocketMessage pong(byte[] bytes) {
        return control(Type.PONG, bytes);
    }

    /** Creates a close control message with a valid RFC 6455 status code and UTF-8 reason. */
    public static WebSocketMessage close(int code, String reason) {
        validateCloseCode(code);
        var payload = Objects.requireNonNull(reason, "reason").getBytes(StandardCharsets.UTF_8);
        if (payload.length > 123) {
            throw new IllegalArgumentException("WebSocket close reason must be at most 123 UTF-8 bytes");
        }
        return new WebSocketMessage(Type.CLOSE, payload, code);
    }

    /** Returns this message's type. */
    public Type type() {
        return type;
    }

    /** Returns a defensive read-only view of this message payload. */
    public ByteBuffer payload() {
        return ByteBuffer.wrap(payload).asReadOnlyBuffer();
    }

    /** Returns a defensive payload copy. */
    public byte[] bytes() {
        return payload.clone();
    }

    /** Returns UTF-8 text for a text message, or empty for other kinds. */
    public Optional<String> text() {
        return type == Type.TEXT ? Optional.of(new String(payload, StandardCharsets.UTF_8)) : Optional.empty();
    }

    /** Returns the close code for a close message, or empty for other kinds. */
    public Optional<Integer> closeCode() {
        return type == Type.CLOSE ? Optional.of(closeCode) : Optional.empty();
    }

    /** Returns the UTF-8 close reason for a close message, or empty for other kinds. */
    public Optional<String> closeReason() {
        return type == Type.CLOSE ? Optional.of(new String(payload, StandardCharsets.UTF_8)) : Optional.empty();
    }

    /** Returns the bytes retained by this immutable value. */
    public int size() {
        return payload.length;
    }

    /** Validates a close status code exposed to application code. */
    public static void validateCloseCode(int code) {
        var applicationCode = code >= 3000 && code <= 4999;
        var standardCode = code == 1000 || code == 1001 || code == 1002 || code == 1003
                || code == 1007 || code == 1008 || code == 1009 || code == 1010 || code == 1011
                || code == 1012 || code == 1013 || code == 1014;
        if (!applicationCode && !standardCode) {
            throw new IllegalArgumentException("Invalid WebSocket close code: " + code);
        }
    }

    private static WebSocketMessage control(Type type, byte[] bytes) {
        var copy = Objects.requireNonNull(bytes, "bytes");
        if (copy.length > 125) {
            throw new IllegalArgumentException("WebSocket control payload must be at most 125 bytes");
        }
        return new WebSocketMessage(type, copy, 0);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WebSocketMessage message
                && type == message.type
                && closeCode == message.closeCode
                && Arrays.equals(payload, message.payload);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * type.hashCode() + closeCode) + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "WebSocketMessage[type=" + type + ", size=" + payload.length + "]";
    }
}
