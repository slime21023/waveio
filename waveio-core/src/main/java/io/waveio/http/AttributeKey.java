package io.waveio.http;

import java.util.Objects;

/**
 * A type-safe identifier for request-scoped attributes.
 *
 * <p>Attributes allow middleware and handlers to attach and retrieve arbitrary contextual data
 * (such as authenticated user identities, tracing tokens, or transaction contexts) to an
 * {@link HttpRequest} in a type-safe manner.
 *
 * @param <T> the type of the value associated with this key
 * @see HttpRequest#setAttribute(AttributeKey, Object)
 * @see HttpRequest#attribute(AttributeKey)
 */
public final class AttributeKey<T> {
    private final String name;

    private AttributeKey(String name) {
        this.name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Attribute name must not be blank");
        }
    }

    /**
     * Creates a new typed attribute key with the given name.
     *
     * @param <T> the value type associated with the key
     * @param name non-blank descriptive name of the attribute
     * @return a new {@code AttributeKey} instance
     * @throws IllegalArgumentException if {@code name} is blank or null
     */
    public static <T> AttributeKey<T> of(String name) {
        return new AttributeKey<>(name);
    }

    /**
     * Returns the name of this attribute key.
     *
     * @return key name
     */
    public String name() {
        return name;
    }
}
