package io.wavejava.wave.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RegistryTest {
    @Test
    void childRegistryOverridesParentAndFallsBackForOtherFrameworkServices() {
        Clock parentClock = () -> "parent";
        Clock childClock = () -> "child";
        var parent = Registry.builder()
                .add(Clock.class, parentClock)
                .add(Formatter.class, new Formatter("plain"))
                .build();
        var child = Registry.builder(parent)
                .add(Clock.class, childClock)
                .build();

        assertSame(childClock, child.require(Clock.class));
        assertEquals("plain", child.require(Formatter.class).name());
        assertTrue(child.contains(Clock.class));
        assertTrue(child.contains(Formatter.class));
        assertFalse(child.contains(Runnable.class));
        assertSame(parent, child.parent().orElseThrow());
    }

    @Test
    void duplicateKeysWithinOneRegistryAreRejectedRatherThanSilentlyOverwritten() {
        var builder = Registry.builder().add(Formatter.class, new Formatter("one"));

        var failure = assertThrows(IllegalStateException.class, () -> builder.add(Formatter.class, new Formatter("two")));

        assertTrue(failure.getMessage().contains(Formatter.class.getTypeName()));
        var registry = builder.build();
        assertEquals("one", registry.require(Formatter.class).name());
        assertThrows(UnsupportedOperationException.class, () -> registry.localTypes().add(Runnable.class));
    }

    @Test
    void missingRequiredServiceNamesTheRequestedFrameworkType() {
        var failure = assertThrows(java.util.NoSuchElementException.class, () -> Registry.empty().require(Formatter.class));

        assertTrue(failure.getMessage().contains(Formatter.class.getTypeName()));
    }

    @FunctionalInterface
    private interface Clock {
        String now();
    }

    private record Formatter(String name) {
    }
}
