package io.wavejava.wave.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.wavejava.wave.Wave;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Fails if an exported contract leaks an implementation or transport type in its public surface. */
class PublicSignatureBoundaryTest {
    @Test
    void exportedPublicSignaturesDoNotLeakImplementationOrSocketTypes() throws Exception {
        try (var classes = Files.walk(compiledClasses())) {
            for (var type : classes.filter(path -> path.toString().endsWith(".class"))
                    .map(PublicSignatureBoundaryTest::className)
                    .filter(name -> name.equals(Wave.class.getName())
                            || name.startsWith("io.wavejava.wave.api.")
                            || name.startsWith("io.wavejava.wave.spi."))
                    .map(PublicSignatureBoundaryTest::load)
                    .filter(type -> Modifier.isPublic(type.getModifiers()))
                    .toList()) {
                var signatures = Stream.concat(
                                Stream.concat(Arrays.stream(type.getGenericInterfaces()), Stream.of(type.getGenericSuperclass())),
                                Stream.concat(
                                        Stream.of(type.getConstructors())
                                                .flatMap(constructor -> Stream.concat(
                                                        Stream.of(constructor.getGenericParameterTypes()),
                                                        Stream.of(constructor.getGenericExceptionTypes()))),
                                        Stream.concat(
                                                Stream.of(type.getFields()).map(field -> field.getGenericType()),
                                                Stream.of(type.getMethods())
                                                        .filter(method -> method.getDeclaringClass().equals(type))
                                                        .flatMap(method -> Stream.concat(
                                                                Stream.of(method.getGenericReturnType()),
                                                                Stream.concat(
                                                                        Stream.of(method.getGenericParameterTypes()),
                                                                        Stream.of(method.getGenericExceptionTypes())))))))
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .toList();
                assertFalse(signatures.stream().anyMatch(PublicSignatureBoundaryTest::isForbidden),
                        () -> type.getName() + " leaks an implementation or socket type: " + signatures);
            }
        }
    }

    @Test
    void rootPackageContainsOnlyWaveAsAPublicType() throws Exception {
        var rootPackage = compiledClasses().resolve(Wave.class.getPackageName().replace('.', '/'));
        try (var classes = Files.list(rootPackage)) {
            var publicTypes = classes
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().equals("module-info.class"))
                    .map(PublicSignatureBoundaryTest::className)
                    .map(PublicSignatureBoundaryTest::load)
                    .filter(type -> Modifier.isPublic(type.getModifiers()))
                    .map(Class::getName)
                    .collect(Collectors.toUnmodifiableSet());
            assertEquals(Set.of(Wave.class.getName()), publicTypes,
                    "the root package is reserved for the single Wave assembly entry point");
        }
    }

    private static Path compiledClasses() throws URISyntaxException {
        var codeSource = Path.of(Wave.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        // Surefire patches named modules with target/test-classes. Public-surface checks must inspect
        // the production module output instead of the patched test output.
        var productionClasses = codeSource.resolveSibling("classes");
        return Files.isDirectory(productionClasses) ? productionClasses : codeSource;
    }

    private static String className(Path path) {
        var relative = compiledClassesUnchecked().relativize(path).toString();
        return relative.substring(0, relative.length() - ".class".length()).replace('\\', '.').replace('/', '.');
    }

    private static Path compiledClassesUnchecked() {
        try {
            return compiledClasses();
        } catch (URISyntaxException failure) {
            throw new IllegalStateException("Could not locate compiled Wave classes", failure);
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, Wave.class.getClassLoader());
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Could not load exported class " + name, failure);
        }
    }

    private static boolean isForbidden(String signature) {
        return signature.contains("io.wavejava.wave.internal.")
                || signature.contains("io.wavejava.wave.runtime.")
                || signature.contains("io.wavejava.wave.netty.")
                || signature.contains("io.netty.")
                || signature.contains("java.net.http.")
                || signature.matches(".*java\\.net\\.(?:Socket|ServerSocket)(?:[ <\\[]|$).*");
    }
}
