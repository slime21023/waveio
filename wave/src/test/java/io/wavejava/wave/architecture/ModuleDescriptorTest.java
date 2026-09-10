package io.wavejava.wave.architecture;

import io.wavejava.wave.Wave;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the exact pre-release JPMS public-surface manifest. */
class ModuleDescriptorTest {
    private static final String MODULE_NAME = "io.wavejava.wave";
    private static final Set<String> EXPORTED_PACKAGES = Set.of(
            "io.wavejava.wave",
            "io.wavejava.wave.api.client",
            "io.wavejava.wave.api.config",
            "io.wavejava.wave.api.file",
            "io.wavejava.wave.api.form",
            "io.wavejava.wave.api.health",
            "io.wavejava.wave.api.http",
            "io.wavejava.wave.api.lifecycle",
            "io.wavejava.wave.api.middleware",
            "io.wavejava.wave.api.multipart",
            "io.wavejava.wave.api.observability",
            "io.wavejava.wave.api.registry",
            "io.wavejava.wave.api.render",
            "io.wavejava.wave.api.resilience",
            "io.wavejava.wave.api.routing",
            "io.wavejava.wave.api.server",
            "io.wavejava.wave.api.session",
            "io.wavejava.wave.api.sse",
            "io.wavejava.wave.api.stream",
            "io.wavejava.wave.api.websocket",
            "io.wavejava.wave.spi",
            "io.wavejava.wave.spi.lifecycle",
            "io.wavejava.wave.spi.render",
            "io.wavejava.wave.spi.session",
            "io.wavejava.wave.spi.testing"
    );
    private static final Set<String> USED_SERVICES = Set.of(
            "io.wavejava.wave.spi.lifecycle.ServiceProvider",
            "io.wavejava.wave.spi.render.ParserProvider",
            "io.wavejava.wave.spi.render.RendererProvider",
            "io.wavejava.wave.spi.session.SessionStoreProvider"
    );
    private static final Set<String> TRANSITIVE_REQUIREMENTS = Set.of();

    @Test
    void moduleMatchesThePreReleasePublicSurfaceManifest() {
        Module module = Wave.class.getModule();

        assertTrue(module.isNamed(), "tests must execute against the named module");
        assertEquals(MODULE_NAME, module.getName());

        ModuleDescriptor descriptor = module.getDescriptor();
        Set<String> exportedPackages = descriptor.exports().stream()
                .map(ModuleDescriptor.Exports::source)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertEquals(EXPORTED_PACKAGES, exportedPackages, "unexpected JPMS export change");
        assertTrue(
                descriptor.exports().stream().allMatch(exportedPackage -> exportedPackage.targets().isEmpty()),
                "public packages must not become qualified JPMS exports"
        );
        assertFalse(descriptor.isOpen(), "the Wave module must not become open");
        assertEquals(Set.of(), descriptor.opens(), "unexpected JPMS opens directive");
        assertEquals(Set.of(), descriptor.provides(), "unexpected JPMS provides directive");
        assertEquals(USED_SERVICES, descriptor.uses(), "unexpected JPMS service use change");

        Set<String> transitiveRequirements = descriptor.requires().stream()
                .filter(requirement -> requirement.modifiers()
                        .contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE))
                .map(ModuleDescriptor.Requires::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertEquals(
                TRANSITIVE_REQUIREMENTS,
                transitiveRequirements,
                        "public JPMS readability changed"
        );
    }
}

