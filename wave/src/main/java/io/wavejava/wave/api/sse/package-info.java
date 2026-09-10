/**
 * Bounded Server-Sent Events contracts layered on the public response {@link java.util.concurrent.Flow}
 * streaming boundary. The 0.5 client contract is deliberately independent from the bounded-byte
 * {@code WaveClient} API so persistent event streams have their own lifecycle and cancellation
 * ownership. In 0.5, {@link io.wavejava.wave.api.sse.SseClient} uses a direct, bounded
 * HTTP/1.1 {@code http} transport only; TLS, proxy tunnelling, and HTTP/2 integration are
 * deliberately deferred to the shared transport work in 0.7.
 */
package io.wavejava.wave.api.sse;
