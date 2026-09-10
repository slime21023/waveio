/**
 * Bounded HTTP client contracts.
 *
 * <p>The public API uses wave HTTP values, {@link java.net.URI}, byte arrays, and completion
 * stages; it deliberately does not expose JDK HTTP request, response, or client types. This
 * initial contract uses fully aggregated byte bodies; Flow request/response streaming belongs to
 * the separate streaming layer and is not implied by this API. A client owns a bounded
 * exchange-admission pool, enforces request/response byte and time budgets, and can be shared
 * safely across callers.</p>
 */
package io.wavejava.wave.api.client;
