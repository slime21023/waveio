/**
 * Public, Netty-free WebSocket endpoint, session, message, and resource-limit contracts.
 *
 * <p>Inbound sessions expose bounded text/binary application data plus an optional ordered
 * terminal close notification when Flow demand remains. Ping and pong are transport control
 * handled independently of application Flow demand; applications may still send them through
 * {@link io.wavejava.wave.api.websocket.WebSocketSession}.</p>
 */
package io.wavejava.wave.api.websocket;
