/**
 * Stable HTTP value types used by wave applications.
 *
 * <p>This package intentionally contains no transport-specific types. In particular, callers never
 * own Netty buffers or channels. Request metadata is immutable, while {@link
 * io.wavejava.wave.api.http.Body} is a bounded, single-consumption view of the request payload and
 * {@link io.wavejava.wave.api.http.Response} is the mutable response builder for one invocation.</p>
 */
package io.wavejava.wave.api.http;
