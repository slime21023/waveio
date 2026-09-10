package io.wavejava.wave.api.websocket;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Finite message, outbound-memory, and close-handshake budgets for one WebSocket session.
 *
 * <p>The server's configured idle timeout is the heartbeat cadence: one idle interval sends a
 * ping, and a second interval without a pong closes the connection. This type separately bounds
 * the close handshake so an unresponsive peer cannot retain a session indefinitely.</p>
 */
public record WebSocketLimits(
        int maximumFrameBytes,
        int maximumMessageBytes,
        int maximumOutboundBytes,
        Duration closeHandshakeTimeout
) {
    // The server reserves a complete frame's payload plus the largest legal server header and a
    // small accounting margin before accepting an application write. Keep this relationship in
    // the public validation so a legal maximum-size frame is never rejected merely because its
    // wire-accounting overhead was omitted from the configured session budget.
    private static final int MAXIMUM_OUTBOUND_FRAME_ACCOUNTING_BYTES = 14;

    /** Default maximum payload size for one wire frame. */
    public static final int DEFAULT_MAXIMUM_FRAME_BYTES = 64 * 1024;

    /** Default maximum size after fragmented data frames have been reassembled. */
    public static final int DEFAULT_MAXIMUM_MESSAGE_BYTES = 1024 * 1024;

    /** Default maximum application-owned outbound bytes awaiting socket write completion. */
    public static final int DEFAULT_MAXIMUM_OUTBOUND_BYTES = 2 * 1024 * 1024;

    /** Default maximum time retained while waiting for the peer close frame. */
    public static final Duration DEFAULT_CLOSE_HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);

    private static final WebSocketLimits DEFAULTS = new WebSocketLimits(
            DEFAULT_MAXIMUM_FRAME_BYTES,
            DEFAULT_MAXIMUM_MESSAGE_BYTES,
            DEFAULT_MAXIMUM_OUTBOUND_BYTES,
            DEFAULT_CLOSE_HANDSHAKE_TIMEOUT);

    /** Validates one immutable WebSocket budget snapshot. */
    public WebSocketLimits {
        maximumFrameBytes = requirePositive(maximumFrameBytes, "maximumFrameBytes");
        maximumMessageBytes = requirePositive(maximumMessageBytes, "maximumMessageBytes");
        maximumOutboundBytes = requirePositive(maximumOutboundBytes, "maximumOutboundBytes");
        closeHandshakeTimeout = requirePositive(closeHandshakeTimeout, "closeHandshakeTimeout");
        if (maximumMessageBytes < maximumFrameBytes) {
            throw new IllegalArgumentException("maximumMessageBytes must be at least maximumFrameBytes");
        }
        if (maximumFrameBytes > maximumOutboundBytes - MAXIMUM_OUTBOUND_FRAME_ACCOUNTING_BYTES) {
            throw new IllegalArgumentException(
                    "maximumOutboundBytes must include maximumFrameBytes plus frame accounting overhead");
        }
    }

    /** Returns the documented bounded defaults. */
    public static WebSocketLimits defaults() {
        return DEFAULTS;
    }

    static List<String> validateSubprotocols(List<String> subprotocols) {
        Objects.requireNonNull(subprotocols, "subprotocols");
        var unique = new LinkedHashSet<String>();
        for (var protocol : subprotocols) {
            var value = Objects.requireNonNull(protocol, "subprotocol");
            if (value.isBlank()) {
                throw new IllegalArgumentException("WebSocket subprotocol must not be blank");
            }
            for (var index = 0; index < value.length(); index++) {
                var character = value.charAt(index);
                if (character <= 0x20 || character >= 0x7f || character == ',' || character == '"') {
                    throw new IllegalArgumentException("Invalid WebSocket subprotocol token: " + value);
                }
            }
            if (!unique.add(value)) {
                throw new IllegalArgumentException("Duplicate WebSocket subprotocol: " + value);
            }
        }
        return List.copyOf(new ArrayList<>(unique));
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero: " + value);
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be greater than zero: " + value);
        }
        return value;
    }
}
