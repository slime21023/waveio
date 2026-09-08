package io.waveio.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RegistryTest {
    @Test
    void keysUseStructuralTypeAndNameEquality() {
        assertEquals(Key.of(String.class, "primary"), Key.of(String.class, "primary"));
        assertThrows(IllegalArgumentException.class, () -> Key.of(int.class, "number"));
        assertThrows(IllegalArgumentException.class, () -> Key.of(Void.TYPE, "void"));
        assertThrows(IllegalArgumentException.class, () -> Key.of(String.class, " "));
    }

    @Test
    void lookupDistinguishesQualifiersAndReportsMissingValues() {
        Registry registry = Registry.builder()
                .bind(Key.of(String.class, "one"), "first")
                .bind(Key.of(String.class, "two"), "second")
                .build();

        assertEquals("first", registry.get(Key.of(String.class, "one")));
        assertEquals("second", registry.get(Key.of(String.class, "two")));
        assertThrows(MissingRegistryEntryException.class, () -> registry.get(Key.of(String.class, "missing")));
    }

    @Test
    void overlayPrefersArgumentAndAllowsNestedScopeRestoration() {
        Key<String> key = Key.of(String.class, "value");
        Registry root = Registry.builder().bind(key, "root").build();
        Registry request = Registry.builder().bind(key, "request").build();
        Registry nested = Registry.builder().bind(key, "nested").build();

        Registry requestView = root.overlay(request);
        assertEquals("request", requestView.get(key));
        assertEquals("nested", requestView.overlay(nested).get(key));
        assertEquals("request", requestView.get(key));
        assertEquals("root", root.get(key));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsInvalidBindingsAndKeepsSnapshotInstances() {
        Key<String> key = Key.of(String.class, "value");
        Registry.Builder builder = Registry.builder();
        assertThrows(NullPointerException.class, () -> builder.bind(key, null));
        assertThrows(IllegalArgumentException.class, () -> builder.bind((Key) key, 42));
        Object service = new Object();
        Registry registry = Registry.builder().bind(Key.of(Object.class, "service"), service).build();
        assertSame(service, registry.get(Key.of(Object.class, "service")));
        Registry.Builder closed = Registry.builder();
        closed.build();
        assertThrows(IllegalStateException.class, () -> closed.bind(key, "late"));
    }

    @Test
    void rejectsDuplicateBindingsAndBuildCreatesAnImmutableSnapshot() {
        Key<String> key = Key.of(String.class, "value");
        Registry.Builder builder = Registry.builder().bind(key, "initial");
        assertThrows(IllegalArgumentException.class, () -> builder.bind(key, "duplicate"));
        Registry registry = builder.build();
        assertEquals("initial", registry.get(key));
        assertThrows(IllegalStateException.class, builder::build);
    }
}
