package io.wavejava.wave.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.wavejava.wave.api.http.Body;
import io.wavejava.wave.api.http.MediaType;
import io.wavejava.wave.api.render.ParseTarget;
import io.wavejava.wave.api.render.Parser;
import io.wavejava.wave.spi.render.ParserProvider;
import io.wavejava.wave.spi.testing.ProviderContract;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** Contract tests for deterministic, bounded SPI provider discovery. */
class SpiProvidersTest {
    @Test
    void ordersProvidersByDescendingPriorityThenStableId() {
        var providers = SpiProviders.validate(TestProvider.class, List.of(
                new Candidate("z-last", 3, "different"),
                new Candidate("b-second", 7, "same"),
                new Candidate("a-first", 7, "other")), 3);

        assertEquals(List.of("a-first", "b-second", "z-last"), providers.stream().map(WaveProvider::id).toList());
    }

    @Test
    void equalPriorityInOneSelectionKeyFailsBeforeStartup() {
        var failure = assertThrows(SpiConfigurationException.class, () -> SpiProviders.validate(
                TestProvider.class,
                List.of(new Candidate("first", 2, "json"), new Candidate("second", 2, "json")),
                4));

        assertEquals(true, failure.getMessage().contains("Ambiguous"));
    }

    @Test
    void duplicateIdsAndFiniteCapsAreRejected() {
        assertThrows(SpiConfigurationException.class, () -> SpiProviders.validate(
                TestProvider.class,
                List.of(new Candidate("same", 1, "one"), new Candidate("same", 2, "two")),
                4));
        assertThrows(SpiConfigurationException.class, () -> SpiProviders.validate(
                TestProvider.class,
                List.of(new Candidate("one", 1, "one"), new Candidate("two", 2, "two")),
                1));
    }

    @Test
    void externalProviderContractDiscoversAServiceResourceFromAnIsolatedLoader() throws Exception {
        var directory = Files.createTempDirectory("wave-spi-provider-");
        try {
            var sourceFile = directory.resolve("external/ExternalParserProvider.java");
            Files.createDirectories(sourceFile.getParent());
            Files.writeString(sourceFile, """
                    package external;
                    import io.wavejava.wave.api.render.Parser;
                    import io.wavejava.wave.spi.render.ParserProvider;
                    public final class ExternalParserProvider implements ParserProvider {
                        public ExternalParserProvider() {}
                        public String id() { return "external.parser"; }
                        public Parser<?> parser() { return null; }
                    }
                    """, StandardCharsets.UTF_8);
            var compiler = ToolProvider.getSystemJavaCompiler();
            assertNotNull(compiler, "provider contract tests require the JDK compiler");
            var waveClasses = java.nio.file.Path.of(
                    ParserProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            assertEquals(0, compiler.run(null, null, null,
                    "-classpath", waveClasses.toString(), "-d", directory.toString(), sourceFile.toString()));
            var serviceFile = directory.resolve("META-INF/services/")
                    .resolve(ParserProvider.class.getName());
            Files.createDirectories(serviceFile.getParent());
            Files.writeString(serviceFile, "external.ExternalParserProvider" + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            try (var loader = new URLClassLoader(new java.net.URL[] {directory.toUri().toURL()},
                    SpiProvidersTest.class.getClassLoader())) {
                assertNotNull(loader.getResource("META-INF/services/" + ParserProvider.class.getName()));
                var discovered = ProviderContract.discover(ParserProvider.class, loader);

                assertEquals(1, discovered.size());
                assertEquals("external.ExternalParserProvider", discovered.getFirst().getClass().getName());
            }
        } finally {
            deleteTree(directory);
        }
    }

    private interface TestProvider extends WaveProvider {
    }

    private record Candidate(String id, int priority, String selectionKey) implements TestProvider {
    }

    private static void deleteTree(java.nio.file.Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
