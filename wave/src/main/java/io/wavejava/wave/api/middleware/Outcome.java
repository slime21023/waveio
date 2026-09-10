package io.wavejava.wave.api.middleware;

import java.util.Objects;
import java.util.Optional;

/**
 * The application-side result of one request invocation.
 *
 * <p>An outcome deliberately separates successful application handling from a response that was
 * later unable to reach the peer. Transport code may report a write failure after middleware has
 * observed {@link Kind#SUCCESS} or {@link Kind#APPLICATION_FAILURE}.</p>
 */
public final class Outcome {
    /** Categories observable by application middleware. */
    public enum Kind {
        SUCCESS,
        APPLICATION_FAILURE,
        DEADLINE_EXCEEDED,
        CLIENT_CANCELLATION,
        TRANSPORT_FAILURE
    }

    private final Kind kind;
    private final Throwable cause;

    private Outcome(Kind kind, Throwable cause) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.cause = cause;
    }

    /** Creates a successful application outcome. */
    public static Outcome success() {
        return new Outcome(Kind.SUCCESS, null);
    }

    /** Creates a failed outcome with its originating cause. */
    public static Outcome failure(Kind kind, Throwable cause) {
        if (kind == Kind.SUCCESS) {
            throw new IllegalArgumentException("success cannot have a failure cause");
        }
        return new Outcome(kind, Objects.requireNonNull(cause, "cause"));
    }

    /** Returns the category of this invocation outcome. */
    public Kind kind() {
        return kind;
    }

    /** Returns the cause when the outcome is not successful. */
    public Optional<Throwable> cause() {
        return Optional.ofNullable(cause);
    }

    /** Returns whether application handling completed successfully. */
    public boolean isSuccess() {
        return kind == Kind.SUCCESS;
    }
}
