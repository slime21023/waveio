package io.waveio.registry;

import java.util.Objects;

/** A typed, qualified key for a registry entry. */
public final class Key<T> {
    private final Class<T> type;
    private final String name;

    private Key(Class<T> type, String name) {
        this.type = type;
        this.name = name;
    }

    /** Creates a key from its runtime type and non-blank qualifier. */
    public static <T> Key<T> of(Class<T> type, String name) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        if (type.isPrimitive() || type == Void.TYPE) {
            throw new IllegalArgumentException("type must be a non-primitive reference type");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return new Key<>(type, name);
    }

    /** Returns the entry runtime type. */
    public Class<T> type() {
        return type;
    }

    /** Returns the entry qualifier. */
    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof Key<?> other && type.equals(other.type) && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, name);
    }

    @Override
    public String toString() {
        return "Key[type=" + type.getName() + ", name=" + name + ']';
    }
}
