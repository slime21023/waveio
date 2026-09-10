/**
 * Immutable application configuration snapshots and deterministic typed binding.
 *
 * <p>Configuration sources are merged only while an application is assembled. A resulting
 * {@link io.wavejava.wave.api.config.Config} is immutable, retains the winning source for every
 * value, and deliberately has no transport, environment, or dependency-injection dependency.</p>
 */
package io.wavejava.wave.api.config;
