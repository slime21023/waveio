package io.wavejava.wave.api.render;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;

/**
 * The requested Java type for a {@link Parser}.
 *
 * <p>A target retains its full reflective {@link Type}, while also exposing its raw class for
 * parser selection. This lets a later registry support parameterized targets without changing the
 * parser contract.</p>
 *
 * @param <T> the value produced by a parser
 */
public final class ParseTarget<T> {
    private final Type type;
    private final Class<?> rawType;

    private ParseTarget(Type type) {
        this.type = Objects.requireNonNull(type, "type");
        rawType = rawTypeOf(type);
    }

    /** Creates a target for a concrete class. */
    public static <T> ParseTarget<T> of(Class<T> type) {
        return new ParseTarget<>(type);
    }

    /**
     * Creates a target for a concrete or parameterized reflective type.
     *
     * <p>Type variables, wildcards, and generic arrays are deliberately rejected because they do
     * not identify a concrete runtime target for parser selection.</p>
     */
    @SuppressWarnings("unchecked")
    public static <T> ParseTarget<T> of(Type type) {
        return (ParseTarget<T>) new ParseTarget<>(type);
    }

    /** Returns the complete requested reflective type. */
    public Type type() {
        return type;
    }

    /** Returns the concrete raw class used to select a parser. */
    public Class<?> rawType() {
        return rawType;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ParseTarget<?> target && type.equals(target.type);
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }

    @Override
    public String toString() {
        return type.getTypeName();
    }

    private static Class<?> rawTypeOf(Type type) {
        if (type instanceof Class<?> rawClass) {
            return rawClass;
        }
        if (type instanceof ParameterizedType parameterizedType
                && parameterizedType.getRawType() instanceof Class<?> rawClass) {
            return rawClass;
        }
        throw new IllegalArgumentException("Parse target must have a concrete raw class: " + type.getTypeName());
    }
}
