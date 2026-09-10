/**
 * The public module for the wave HTTP application framework.
 *
 * <p>Only the root entry point, {@code api.*}, and {@code spi.*} packages are exported.
 * Transport and invocation implementation packages deliberately remain encapsulated.</p>
 */
module io.wavejava.wave {
    requires com.fasterxml.jackson.databind;
    requires java.net.http;
    requires static java.compiler;
    requires io.netty.buffer;
    requires io.netty.codec;
    requires io.netty.codec.http;
    requires io.netty.codec.http2;
    requires io.netty.common;
    requires io.netty.handler;
    requires io.netty.transport;
    requires org.slf4j;

    exports io.wavejava.wave;
    exports io.wavejava.wave.api.application;
    exports io.wavejava.wave.api.file;
    exports io.wavejava.wave.api.form;
    exports io.wavejava.wave.api.health;
    exports io.wavejava.wave.api.http;
    exports io.wavejava.wave.api.config;
    exports io.wavejava.wave.api.client;
    exports io.wavejava.wave.api.lifecycle;
    exports io.wavejava.wave.api.middleware;
    exports io.wavejava.wave.api.multipart;
    exports io.wavejava.wave.api.observability;
    exports io.wavejava.wave.api.registry;
    exports io.wavejava.wave.api.render;
    exports io.wavejava.wave.api.resilience;
    exports io.wavejava.wave.api.routing;
    exports io.wavejava.wave.api.server;
    exports io.wavejava.wave.api.session;
    exports io.wavejava.wave.api.sse;
    exports io.wavejava.wave.api.websocket;
    exports io.wavejava.wave.spi;
    exports io.wavejava.wave.spi.lifecycle;
    exports io.wavejava.wave.spi.render;
    exports io.wavejava.wave.spi.session;
    exports io.wavejava.wave.spi.testing;

    uses io.wavejava.wave.spi.lifecycle.ServiceProvider;
    uses io.wavejava.wave.spi.render.ParserProvider;
    uses io.wavejava.wave.spi.render.RendererProvider;
    uses io.wavejava.wave.spi.session.SessionStoreProvider;
}
