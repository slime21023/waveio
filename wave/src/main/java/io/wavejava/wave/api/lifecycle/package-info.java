/**
 * Dependency-aware asynchronous lifecycle contracts for framework services.
 *
 * <p>Service startup is ordered by declared dependencies. A startup failure rolls back services
 * that did start in reverse order; normal shutdown also proceeds in reverse order and attempts
 * every remaining service even when one stop operation fails.</p>
 */
package io.wavejava.wave.api.lifecycle;
