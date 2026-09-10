package io.wavejava.wave.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Keeps the JPMS boundary meaningful at compile time as the single artifact grows.
 *
 * <p>JPMS controls what consumers can access. These rules additionally prevent a public API from
 * accidentally acquiring an implementation dependency that would be impossible to remove later.</p>
 */
@AnalyzeClasses(
        packages = "io.wavejava.wave",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ModuleBoundaryTest {
    @ArchTest
    static final ArchRule publicContractsDoNotDependOnImplementation = noClasses()
            .that().resideInAnyPackage("io.wavejava.wave.api..", "io.wavejava.wave.spi..")
            .and().haveNameNotMatching("io.wavejava.wave.api.client.WaveClient(\\$.*)?")
            .and().haveNameNotMatching("io.wavejava.wave.api.websocket.WebSocketClient(\\$.*)?")
            .and().haveNameNotMatching("io.wavejava.wave.api.http.Response(\\$.*)?")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "io.wavejava.wave.runtime..",
                    "io.wavejava.wave.netty..",
                    "io.wavejava.wave.internal.."
            );

    @ArchTest
    static final ArchRule runtimeDoesNotDependOnNettyTransport = noClasses()
            .that().resideInAPackage("io.wavejava.wave.runtime..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("io.wavejava.wave.netty..")
            .allowEmptyShould(true);
}
