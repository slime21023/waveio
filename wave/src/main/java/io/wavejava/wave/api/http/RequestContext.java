package io.wavejava.wave.api.http;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable execution metadata associated with one request invocation.
 *
 * <p>Context data, request ID, and deadline are snapshotted at construction. The associated
 * {@link CancellationToken} is deliberately shared and remains mutable so all participants in an
 * invocation can observe the same one-way cancellation signal.</p>
 */
public final class RequestContext {
    private final String requestId;
    private final Map<String, Object> data;
    private final Deadline deadline;
    private final CancellationToken cancellationToken;

    private RequestContext(Builder builder) {
        requestId = validateRequestId(builder.requestId);
        data = immutableData(builder.data);
        deadline = builder.deadline;
        cancellationToken = Objects.requireNonNull(builder.cancellationToken, "cancellationToken");
    }

    /** Starts a context builder. A request ID must be supplied before {@link Builder#build()}. */
    public static Builder builder() {
        return new Builder();
    }

    /** Starts a context builder with its request ID already set. */
    public static Builder builder(String requestId) {
        return builder().requestId(requestId);
    }

    /** Creates a context with the supplied request ID and no deadline or context data. */
    public static RequestContext of(String requestId) {
        return builder(requestId).build();
    }

    /** Returns the stable identifier for this request invocation. */
    public String requestId() {
        return requestId;
    }

    /** Returns an immutable snapshot of application-defined request data. */
    public Map<String, Object> data() {
        return data;
    }

    /** Returns one application-defined context value, if present. */
    public Optional<Object> data(String key) {
        return Optional.ofNullable(data.get(validateDataKey(key)));
    }

    /** Returns the invocation deadline, if one was configured. */
    public Optional<Deadline> deadline() {
        return Optional.ofNullable(deadline);
    }

    /** Returns the shared cooperative cancellation signal for this invocation. */
    public CancellationToken cancellationToken() {
        return cancellationToken;
    }

    /** Returns whether cancellation has been requested. */
    public boolean isCancelled() {
        return cancellationToken.isCancelled();
    }

    /** Returns the first cancellation reason, if cancellation has been requested. */
    public Optional<String> cancellationReason() {
        return cancellationToken.reason();
    }

    /** Returns whether the configured deadline has elapsed according to the system clock. */
    public boolean isDeadlineExceeded() {
        return deadline != null && deadline.isExpired();
    }

    /** Returns whether the configured deadline has elapsed at {@code now}. */
    public boolean isDeadlineExceeded(Instant now) {
        Objects.requireNonNull(now, "now");
        return deadline != null && deadline.isExpired(now);
    }

    private static String validateRequestId(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID must not be blank");
        }
        return requestId;
    }

    private static Map<String, Object> immutableData(Map<String, Object> data) {
        var copy = new LinkedHashMap<String, Object>();
        data.forEach((key, value) -> copy.put(validateDataKey(key), Objects.requireNonNull(value, "context data value")));
        return Collections.unmodifiableMap(copy);
    }

    private static String validateDataKey(String key) {
        Objects.requireNonNull(key, "context data key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("Context data key must not be blank");
        }
        return key;
    }

    /** Builder for an immutable {@link RequestContext}. */
    public static final class Builder {
        private String requestId;
        private final Map<String, Object> data = new LinkedHashMap<>();
        private Deadline deadline;
        private CancellationToken cancellationToken = CancellationToken.create();

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        /** Replaces this builder's context data with an owned snapshot of {@code data}. */
        public Builder data(Map<String, ?> data) {
            Objects.requireNonNull(data, "data");
            this.data.clear();
            data.forEach((key, value) -> this.data.put(validateDataKey(key), Objects.requireNonNull(value, "context data value")));
            return this;
        }

        /** Adds or replaces one application-defined context value. */
        public Builder data(String key, Object value) {
            data.put(validateDataKey(key), Objects.requireNonNull(value, "context data value"));
            return this;
        }

        /** Sets the optional request deadline. */
        public Builder deadline(Deadline deadline) {
            this.deadline = Objects.requireNonNull(deadline, "deadline");
            return this;
        }

        /** Uses the supplied cooperative cancellation token for this context. */
        public Builder cancellationToken(CancellationToken cancellationToken) {
            this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
            return this;
        }

        /** Builds an immutable context snapshot. */
        public RequestContext build() {
            return new RequestContext(this);
        }
    }
}
