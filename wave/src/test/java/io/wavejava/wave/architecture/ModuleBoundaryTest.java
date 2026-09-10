package io.wavejava.wave.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

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

    @ArchTest
    static final ArchRule nettyDoesNotDependOnTheRootEntryPoint = noClasses()
            .that().resideInAPackage("io.wavejava.wave.netty..")
            .should().dependOnClassesThat()
            .resideInAPackage("io.wavejava.wave")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule nettyServerHandleDoesNotOwnApplicationInvocation = noClasses()
            .that().haveFullyQualifiedName("io.wavejava.wave.netty.NettyServerHandle")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("io.wavejava.wave.runtime.InvocationRuntime")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule nettyServerHandleDoesNotOwnServiceLifecycle = noClasses()
            .that().haveFullyQualifiedName("io.wavejava.wave.netty.NettyServerHandle")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("io.wavejava.wave.api.lifecycle.ServiceLifecycle")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule nettyServerHandleDoesNotOwnObservability = noClasses()
            .that().haveFullyQualifiedName("io.wavejava.wave.netty.NettyServerHandle")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("io.wavejava.wave.runtime.ObservabilityDispatcher")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule onlyWaveUsesTheBuiltInFactory = noClasses()
            .that().resideInAPackage("io.wavejava.wave")
            .and().haveNameNotMatching("io.wavejava.wave.Wave")
            .should().dependOnClassesThat()
            .resideInAPackage("io.wavejava.wave.internal.bootstrap")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule publicApiSlicesAreAcyclic = slices()
            .matching("io.wavejava.wave.api.(*)..")
            .should().beFreeOfCycles();
}
