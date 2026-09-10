package io.wavejava.wave.api.websocket;

import io.wavejava.wave.api.http.Request;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * A bounded, duplex WebSocket application session.
 *
 * <p>Inbound delivery has one subscriber and runs outside the transport event loop. Outbound
 * operations copy application data before it reaches the transport and fail deterministically
 * when the endpoint's finite outbound-byte budget cannot admit another frame.</p>
 */
public interface WebSocketSession {
    /** Returns the immutable HTTP request that successfully initiated this session. */
    Request request();

    /**
     * Returns the single-subscriber Flow source of inbound application data and terminal peer close.
     *
     * <p>The source emits {@link WebSocketMessage.Type#TEXT TEXT} and
     * {@link WebSocketMessage.Type#BINARY BINARY} messages subject to demand. Transport Ping/Pong
     * frames are handled by the session so heartbeat and close progress never wait behind a slow
     * application subscriber. When the peer closes while demand remains, the source emits one
     * final {@link WebSocketMessage.Type#CLOSE CLOSE} message before {@code onComplete}; when no
     * demand remains it drops that optional notification and completes immediately. Disconnect,
     * shutdown, and protocol failure instead signal {@code onError}. Subscriber callbacks never
     * run on the transport event loop.</p>
     */
    Flow.Publisher<WebSocketMessage> inbound();

    /** Queues one bounded outbound application message. */
    CompletionStage<Void> send(WebSocketMessage message);

    /** Queues a UTF-8 text message. */
    default CompletionStage<Void> sendText(String text) {
        return send(WebSocketMessage.text(text));
    }

    /** Queues a binary message from the remaining bytes in {@code bytes}. */
    default CompletionStage<Void> sendBinary(ByteBuffer bytes) {
        return send(WebSocketMessage.binary(bytes));
    }

    /** Queues a ping control message. */
    default CompletionStage<Void> ping(byte[] payload) {
        return send(WebSocketMessage.ping(payload));
    }

    /** Queues a pong control message. */
    default CompletionStage<Void> pong(byte[] payload) {
        return send(WebSocketMessage.pong(payload));
    }

    /** Begins the close handshake with a valid close code and reason. */
    CompletionStage<Void> close(int code, String reason);

    /** Completes only after the transport connection has fully closed. */
    CompletionStage<Void> closed();

    /** Returns whether the underlying transport is still available for application messages. */
    boolean isOpen();
}
