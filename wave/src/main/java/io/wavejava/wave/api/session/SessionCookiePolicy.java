package io.wavejava.wave.api.session;

import io.wavejava.wave.api.http.Cookie;
import java.util.Objects;

/** Cookie attributes and bounded wire policy for signed server-side session identifiers. */
public record SessionCookiePolicy(
        String name,
        String path,
        boolean secure,
        boolean httpOnly,
        Cookie.SameSite sameSite,
        int maximumEncodedBytes) {
    /** Creates secure defaults suitable for a production HTTPS deployment. */
    public static SessionCookiePolicy defaults() {
        return new SessionCookiePolicy("wave-session", "/", true, true, Cookie.SameSite.LAX, 512);
    }

    public SessionCookiePolicy {
        // Reuse the HTTP cookie validator rather than maintaining a second token grammar here.
        Cookie.of(Objects.requireNonNull(name, "name"), "x");
        var cookiePath = Objects.requireNonNull(path, "path");
        Cookie.builder(name, "x").path(cookiePath).build();
        sameSite = Objects.requireNonNull(sameSite, "sameSite");
        if (sameSite == Cookie.SameSite.NONE && !secure) {
            throw new IllegalArgumentException("SameSite=None session cookies must be Secure");
        }
        if (maximumEncodedBytes <= 0 || maximumEncodedBytes > 4096) {
            throw new IllegalArgumentException("maximumEncodedBytes must be between 1 and 4096");
        }
    }
}
