package io.wavejava.wave.api.http;

import java.util.Objects;

/**
 * An HTTP request method token.
 *
 * <p>The HTTP grammar permits extension methods, so this is a value type instead of an enum. Method
 * tokens are case-sensitive; the predefined constants use their conventional upper-case spelling.</p>
 */
public final class HttpMethod implements Comparable<HttpMethod> {
    public static final HttpMethod GET = new HttpMethod("GET");
    public static final HttpMethod HEAD = new HttpMethod("HEAD");
    public static final HttpMethod POST = new HttpMethod("POST");
    public static final HttpMethod PUT = new HttpMethod("PUT");
    public static final HttpMethod PATCH = new HttpMethod("PATCH");
    public static final HttpMethod DELETE = new HttpMethod("DELETE");
    public static final HttpMethod OPTIONS = new HttpMethod("OPTIONS");
    public static final HttpMethod CONNECT = new HttpMethod("CONNECT");
    public static final HttpMethod TRACE = new HttpMethod("TRACE");

    private final String name;

    private HttpMethod(String name) {
        this.name = name;
    }

    /**
     * Creates a method from an RFC token. No case conversion is performed.
     *
     * @param name an HTTP method token
     * @return a method value
     * @throws IllegalArgumentException if {@code name} is not a valid HTTP token
     */
    public static HttpMethod of(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("HTTP method must not be empty");
        }
        for (var index = 0; index < name.length(); index++) {
            if (!isTokenCharacter(name.charAt(index))) {
                throw new IllegalArgumentException("Invalid HTTP method token: " + name);
            }
        }
        return new HttpMethod(name);
    }

    /** Returns the wire-format method token. */
    public String name() {
        return name;
    }

    @Override
    public int compareTo(HttpMethod other) {
        return name.compareTo(Objects.requireNonNull(other, "other").name);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HttpMethod method && name.equals(method.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }

    static boolean isTokenCharacter(char character) {
        return character >= '0' && character <= '9'
                || character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || switch (character) {
                    case '!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~' -> true;
                    default -> false;
                };
    }
}
