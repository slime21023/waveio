package io.wavejava.wave.api.http;

/**
 * A protocol-specific HTTP response upgrade.
 *
 * <p>The HTTP response contract records the upgrade without depending on a concrete protocol.
 * Wave's WebSocket endpoint is the current implementation. Custom implementations are unsupported
 * until Wave defines their transport contract.</p>
 */
public interface ResponseUpgrade {
}
