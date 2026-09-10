/**
 * Stable, controlled extension contracts for Wave providers.
 *
 * <p>Providers depend only on exported {@code api.*} and {@code spi.*} packages. They are loaded
 * and validated during application assembly; they must not retain requests, invoke Netty, or make
 * classpath order part of their precedence semantics.</p>
 */
package io.wavejava.wave.spi;
