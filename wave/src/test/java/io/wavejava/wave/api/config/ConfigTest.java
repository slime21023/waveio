package io.wavejava.wave.api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigTest {
    @Test
    void laterSourcesWinDeterministicallyAndRetainWinningProvenance() {
        var defaults = new LinkedHashMap<String, Object>();
        defaults.put("server.port", 8080);
        defaults.put("feature.enabled", false);
        var environment = new LinkedHashMap<String, Object>();
        environment.put("server.port", 9090);
        environment.put("server.name", "edge");

        var config = Config.of(
                ConfigSource.of("defaults", defaults),
                ConfigSource.of("environment", environment));

        assertEquals("9090", config.require("server.port"));
        assertEquals("false", config.require("feature.enabled"));
        assertEquals("edge", config.require("server.name"));
        assertEquals("environment", config.provenance("server.port").orElseThrow().sourceName());
        assertEquals(1, config.provenance("server.port").orElseThrow().precedence());
        assertEquals("defaults", config.provenance("feature.enabled").orElseThrow().sourceName());
        assertEquals(
                java.util.List.of("feature.enabled", "server.name", "server.port"),
                config.values().keySet().stream().toList());

        defaults.put("server.port", 1);
        assertEquals("9090", config.require("server.port"));
        assertThrows(UnsupportedOperationException.class, () -> config.values().put("new.value", "no"));
    }

    @Test
    void recordBindingSupportsNestedRecordsAndDocumentedScalarTypes() {
        var config = Config.of(ConfigSource.of("overrides", Map.of(
                "server.port", "8443",
                "server.enabled", "TRUE",
                "server.timeout", "PT2S",
                "server.mode", "production",
                "server.tls.key", "certs/server.key")));

        var server = config.bind("server", ServerSettings.class);

        assertEquals(8443, server.port());
        assertTrue(server.enabled());
        assertEquals(Duration.ofSeconds(2), server.timeout());
        assertEquals(Mode.PRODUCTION, server.mode());
        assertEquals(java.nio.file.Path.of("certs/server.key"), server.tls().key());
        assertEquals(8443, ConfigBinder.bind(config, "server.port", int.class));
    }

    @Test
    void bindingFailureContainsPathExpectedTypeRawValueAndWinningSource() {
        var config = Config.of(
                ConfigSource.of("defaults", Map.of("server.port", "8080")),
                ConfigSource.of("programmatic-override", Map.of("server.port", "not-a-port")));

        var failure = assertThrows(ConfigBindingException.class, () -> config.bind("server.port", int.class));

        assertEquals("server.port", failure.path());
        assertEquals(int.class, failure.expectedType());
        assertEquals("not-a-port", failure.rawValue().orElseThrow());
        assertEquals("programmatic-override", failure.provenance().orElseThrow().sourceName());
        assertTrue(failure.getMessage().contains("server.port"));
        assertTrue(failure.getMessage().contains("programmatic-override"));
        assertTrue(failure.getMessage().contains("int"));

        var missing = assertThrows(ConfigBindingException.class, () -> config.bind("server.host", String.class));
        assertEquals("server.host", missing.path());
        assertFalse(missing.provenance().isPresent());
    }

    @Test
    void topLevelRecordBindingUsesRootComponentPathsAndRejectsAmbiguousScalarBinding() {
        var config = Config.of(ConfigSource.of("defaults", Map.of("name", "wave", "workers", "4")));

        assertEquals(new ApplicationSettings("wave", 4), config.bind(ApplicationSettings.class));
        var failure = assertThrows(ConfigBindingException.class, () -> config.bind(String.class));
        assertEquals("", failure.path());
        assertTrue(failure.getMessage().contains("explicit configuration path"));
    }

    private record ServerSettings(int port, boolean enabled, Duration timeout, Mode mode, TlsSettings tls) {
    }

    private record TlsSettings(java.nio.file.Path key) {
    }

    private record ApplicationSettings(String name, int workers) {
    }

    private enum Mode {
        DEVELOPMENT,
        PRODUCTION
    }
}
