package io.wavejava.wave.api.client;

import java.util.Objects;

/**
 * Immutable policy controlling automatic HTTP redirect following.
 *
 * <p>A malformed {@code Location}, or one that resolves to an opaque, hostless, or non-HTTP(S)
 * URI, is never followed. The client returns that original redirect response to the caller.</p>
 */
public final class RedirectPolicy {
    /** Redirect behavior mode. */
    public enum Mode {
        /** Never follow a redirect response. */
        NEVER,
        /** Follow only GET and HEAD redirects, preserving retry safety by default. */
        NORMAL,
        /** Follow redirects for any request method; callers explicitly accept replay semantics. */
        ALWAYS
    }

    private static final RedirectPolicy NEVER = new RedirectPolicy(Mode.NEVER, 0);
    private static final RedirectPolicy NORMAL = new RedirectPolicy(Mode.NORMAL, 5);

    private final Mode mode;
    private final int maximumRedirects;

    private RedirectPolicy(Mode mode, int maximumRedirects) {
        this.mode = Objects.requireNonNull(mode, "mode");
        if (maximumRedirects < 0) {
            throw new IllegalArgumentException("maximumRedirects must not be negative: " + maximumRedirects);
        }
        if (mode == Mode.NEVER && maximumRedirects != 0) {
            throw new IllegalArgumentException("NEVER redirect mode must use maximumRedirects of zero");
        }
        this.maximumRedirects = maximumRedirects;
    }

    /** Returns a policy that returns every 3xx response to the caller. */
    public static RedirectPolicy never() {
        return NEVER;
    }

    /** Returns a policy that follows up to five GET/HEAD redirects. */
    public static RedirectPolicy normal() {
        return NORMAL;
    }

    /** Starts a builder for an explicit redirect policy. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the redirect mode. */
    public Mode mode() {
        return mode;
    }

    /** Returns the maximum number of redirects after the initial response. */
    public int maximumRedirects() {
        return maximumRedirects;
    }

    boolean allows(ClientRequest request, int statusCode, int redirectsFollowed) {
        Objects.requireNonNull(request, "request");
        if (mode == Mode.NEVER || redirectsFollowed >= maximumRedirects || !isRedirectStatus(statusCode)) {
            return false;
        }
        return mode == Mode.ALWAYS || request.method().name().equals("GET") || request.method().name().equals("HEAD");
    }

    static boolean isRedirectStatus(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    /** Builder for immutable redirect-policy snapshots. */
    public static final class Builder {
        private Mode mode = Mode.NEVER;
        private int maximumRedirects;

        private Builder() {
        }

        /** Selects redirect behavior. Choosing NEVER resets the redirect limit to zero. */
        public Builder mode(Mode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            if (mode == Mode.NEVER) {
                maximumRedirects = 0;
            } else if (maximumRedirects == 0) {
                maximumRedirects = 5;
            }
            return this;
        }

        /** Sets the finite redirect limit for NORMAL or ALWAYS modes. */
        public Builder maximumRedirects(int maximumRedirects) {
            if (maximumRedirects < 0) {
                throw new IllegalArgumentException("maximumRedirects must not be negative: " + maximumRedirects);
            }
            this.maximumRedirects = maximumRedirects;
            return this;
        }

        /** Builds an immutable redirect policy. */
        public RedirectPolicy build() {
            return new RedirectPolicy(mode, maximumRedirects);
        }
    }
}
