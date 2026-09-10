package io.wavejava.wave.api.config;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Binds immutable configuration snapshots to scalar values and public Java records.
 *
 * <p>Record components map to dotted paths: binding {@code Server} below {@code server} binds a
 * {@code port} component from {@code server.port}. Nested records extend that prefix recursively.
 * The binder deliberately does not instantiate mutable beans, scan classes, or coerce arbitrary
 * methods. Supported scalar types are {@link String}, primitive/wrapper numeric types,
 * {@link Boolean}, {@link Character}, {@link Duration}, {@link URI}, {@link Path}, and enums.</p>
 */
public final class ConfigBinder {
    private ConfigBinder() {
    }

    /** Binds a top-level scalar or record. A top-level scalar needs a path and is therefore rejected. */
    public static <T> T bind(Config config, Class<T> type) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(type, "type");
        if (!type.isRecord()) {
            throw ConfigBindingException.invalid(
                    "",
                    type,
                    null,
                    null,
                    "a top-level scalar requires an explicit configuration path",
                    null);
        }
        return bindRecord(config, "", type);
    }

    /** Binds a scalar or record under {@code path}. */
    public static <T> T bind(Config config, String path, Class<T> type) {
        Objects.requireNonNull(config, "config");
        var normalizedPath = ConfigPath.requirePath(path);
        Objects.requireNonNull(type, "type");
        if (type.isRecord()) {
            return bindRecord(config, normalizedPath, type);
        }
        @SuppressWarnings("unchecked")
        var value = (T) bindScalar(config, normalizedPath, type);
        return value;
    }

    private static <T> T bindRecord(Config config, String prefix, Class<T> type) {
        var components = type.getRecordComponents();
        var arguments = new Object[components.length];
        var parameterTypes = new Class<?>[components.length];
        for (var index = 0; index < components.length; index++) {
            var component = components[index];
            var componentPath = ConfigPath.child(prefix, component.getName());
            parameterTypes[index] = component.getType();
            arguments[index] = component.getType().isRecord()
                    ? bindRecord(config, componentPath, component.getType())
                    : bindScalar(config, componentPath, component.getType());
        }
        try {
            var constructor = type.getDeclaredConstructor(parameterTypes);
            if (!constructor.canAccess(null) && !constructor.trySetAccessible()) {
                throw new IllegalAccessException("record canonical constructor is not accessible");
            }
            return constructor.newInstance(arguments);
        } catch (NoSuchMethodException failure) {
            throw ConfigBindingException.invalid(
                    prefix,
                    type,
                    null,
                    config.firstProvenanceAtOrBelow(prefix).orElse(null),
                    "record canonical constructor could not be found",
                    failure);
        } catch (InstantiationException | IllegalAccessException failure) {
            throw ConfigBindingException.invalid(
                    prefix,
                    type,
                    null,
                    config.firstProvenanceAtOrBelow(prefix).orElse(null),
                    "record canonical constructor could not be invoked",
                    failure);
        } catch (InvocationTargetException failure) {
            var cause = failure.getCause() == null ? failure : failure.getCause();
            throw ConfigBindingException.invalid(
                    prefix,
                    type,
                    null,
                    config.firstProvenanceAtOrBelow(prefix).orElse(null),
                    "record constructor rejected the bound values: " + cause.getMessage(),
                    cause);
        } catch (SecurityException failure) {
            throw ConfigBindingException.invalid(
                    prefix,
                    type,
                    null,
                    config.firstProvenanceAtOrBelow(prefix).orElse(null),
                    "record canonical constructor is not accessible",
                    failure);
        }
    }

    private static Object bindScalar(Config config, String path, Class<?> type) {
        var entry = config.entry(path).orElseThrow(() -> ConfigBindingException.missing(path, type));
        var rawValue = entry.value();
        try {
            if (type == String.class) {
                return rawValue;
            }
            if (type == Integer.class || type == int.class) {
                return Integer.valueOf(rawValue);
            }
            if (type == Long.class || type == long.class) {
                return Long.valueOf(rawValue);
            }
            if (type == Short.class || type == short.class) {
                return Short.valueOf(rawValue);
            }
            if (type == Byte.class || type == byte.class) {
                return Byte.valueOf(rawValue);
            }
            if (type == Double.class || type == double.class) {
                return Double.valueOf(rawValue);
            }
            if (type == Float.class || type == float.class) {
                return Float.valueOf(rawValue);
            }
            if (type == Boolean.class || type == boolean.class) {
                if (rawValue.equalsIgnoreCase("true")) {
                    return true;
                }
                if (rawValue.equalsIgnoreCase("false")) {
                    return false;
                }
                throw new IllegalArgumentException("expected 'true' or 'false'");
            }
            if (type == Character.class || type == char.class) {
                if (rawValue.length() != 1) {
                    throw new IllegalArgumentException("expected exactly one character");
                }
                return rawValue.charAt(0);
            }
            if (type == Duration.class) {
                return Duration.parse(rawValue);
            }
            if (type == URI.class) {
                return URI.create(rawValue);
            }
            if (type == Path.class) {
                return Path.of(rawValue);
            }
            if (type.isEnum()) {
                return bindEnum(type, rawValue);
            }
            throw new UnsupportedOperationException("unsupported scalar type");
        } catch (RuntimeException failure) {
            throw ConfigBindingException.invalid(
                    path,
                    type,
                    rawValue,
                    entry.provenance(),
                    describeFailure(failure),
                    failure);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object bindEnum(Class<?> type, String rawValue) {
        try {
            return Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), rawValue);
        } catch (IllegalArgumentException failure) {
            for (var constant : type.getEnumConstants()) {
                if (((Enum) constant).name().equalsIgnoreCase(rawValue)) {
                    return constant;
                }
            }
            throw failure;
        }
    }

    private static String describeFailure(RuntimeException failure) {
        var message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        }
        return message;
    }
}
