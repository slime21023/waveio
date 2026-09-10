package io.wavejava.wave.api.http;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** An immutable RFC 9457-style problem response payload. */
public final class Problem {
    /** The standard type used when no more specific problem type is supplied. */
    public static final URI ABOUT_BLANK = URI.create("about:blank");

    private final int status;
    private final String title;
    private final URI type;
    private final String detail;
    private final URI instance;
    private final Map<String, Object> extensions;

    private Problem(Builder builder) {
        status = validateStatus(builder.status);
        title = requireText(builder.title, "title");
        type = builder.type;
        detail = builder.detail;
        instance = builder.instance;
        extensions = Collections.unmodifiableMap(new LinkedHashMap<>(builder.extensions));
    }

    /** Creates a problem using {@code about:blank} as its type. */
    public static Problem of(int status, String title) {
        return builder(status, title).build();
    }

    /** Starts construction of a problem payload. */
    public static Builder builder(int status, String title) {
        return new Builder(status, title);
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    public URI type() {
        return type;
    }

    public Optional<String> detail() {
        return Optional.ofNullable(detail);
    }

    public Optional<URI> instance() {
        return Optional.ofNullable(instance);
    }

    public Map<String, Object> extensions() {
        return extensions;
    }

    public Problem withDetail(String detail) {
        return copy().detail(detail).build();
    }

    public Problem withInstance(URI instance) {
        return copy().instance(instance).build();
    }

    public Problem withType(URI type) {
        return copy().type(type).build();
    }

    public Problem withExtension(String name, Object value) {
        return copy().extension(name, value).build();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Problem problem
                && status == problem.status
                && title.equals(problem.title)
                && type.equals(problem.type)
                && Objects.equals(detail, problem.detail)
                && Objects.equals(instance, problem.instance)
                && extensions.equals(problem.extensions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, title, type, detail, instance, extensions);
    }

    private Builder copy() {
        var builder = new Builder(status, title).type(type);
        if (detail != null) {
            builder.detail(detail);
        }
        if (instance != null) {
            builder.instance(instance);
        }
        extensions.forEach(builder::extension);
        return builder;
    }

    static int validateStatus(int status) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("HTTP status must be between 100 and 599: " + status);
        }
        return status;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Builder for {@link Problem}. */
    public static final class Builder {
        private final int status;
        private final String title;
        private URI type = ABOUT_BLANK;
        private String detail;
        private URI instance;
        private final Map<String, Object> extensions = new LinkedHashMap<>();

        private Builder(int status, String title) {
            this.status = status;
            this.title = title;
        }

        public Builder type(URI type) {
            this.type = Objects.requireNonNull(type, "type");
            return this;
        }

        public Builder detail(String detail) {
            this.detail = requireText(detail, "detail");
            return this;
        }

        public Builder instance(URI instance) {
            this.instance = Objects.requireNonNull(instance, "instance");
            return this;
        }

        public Builder extension(String name, Object value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (name.isBlank() || isReserved(name)) {
                throw new IllegalArgumentException("Problem extension name is invalid or reserved: " + name);
            }
            extensions.put(name, value);
            return this;
        }

        public Problem build() {
            return new Problem(this);
        }
    }

    private static boolean isReserved(String name) {
        return switch (name) {
            case "type", "title", "status", "detail", "instance" -> true;
            default -> false;
        };
    }
}
