package io.wavejava.wave.api.session;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Mutable state for one request-owned server-side session.
 *
 * <p>A {@link SessionStore} always snapshots this object at its boundary, so a session loaded by
 * one request is never a mutable alias of another request's stored state. Attribute mutations are
 * synchronized and checked against the supplied {@link SessionPolicy}; arbitrary object graphs are
 * intentionally not accepted as session values.</p>
 */
public final class Session {
    private SessionId id;
    private final Map<String, String> attributes;
    private final Instant createdAt;
    private Instant lastAccessedAt;
    private Instant expiresAt;
    private Instant rotatedAt;
    private boolean invalidated;

    private Session(
            SessionId id,
            Map<String, String> attributes,
            Instant createdAt,
            Instant lastAccessedAt,
            Instant expiresAt,
            Instant rotatedAt,
            boolean invalidated) {
        this.id = Objects.requireNonNull(id, "id");
        this.attributes = new LinkedHashMap<>(Objects.requireNonNull(attributes, "attributes"));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.lastAccessedAt = Objects.requireNonNull(lastAccessedAt, "lastAccessedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.rotatedAt = Objects.requireNonNull(rotatedAt, "rotatedAt");
        this.invalidated = invalidated;
    }

    /** Creates a new empty session with finite idle and absolute expiry. */
    public static Session create(SessionId id, SessionPolicy policy, Instant now) {
        var issuedAt = Objects.requireNonNull(now, "now");
        var configured = Objects.requireNonNull(policy, "policy");
        return new Session(
                Objects.requireNonNull(id, "id"),
                Map.of(),
                issuedAt,
                issuedAt,
                expiry(issuedAt, issuedAt, configured),
                issuedAt,
                false);
    }

    /** Returns this session's current opaque identifier. */
    public synchronized SessionId id() {
        return id;
    }

    /** Returns the immutable creation time. */
    public Instant createdAt() {
        return createdAt;
    }

    /** Returns the most recent successfully touched time. */
    public synchronized Instant lastAccessedAt() {
        return lastAccessedAt;
    }

    /** Returns the effective minimum of idle and absolute expiry. */
    public synchronized Instant expiresAt() {
        return expiresAt;
    }

    /** Returns the time at which this session identifier was last issued or rotated. */
    public synchronized Instant rotatedAt() {
        return rotatedAt;
    }

    /** Returns an immutable attribute snapshot. */
    public synchronized Map<String, String> attributes() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** Looks up one string attribute. */
    public synchronized Optional<String> attribute(String name) {
        return Optional.ofNullable(attributes.get(validateAttributeName(name)));
    }

    /** Stores or replaces one bounded string attribute. */
    public synchronized Session put(String name, String value, SessionPolicy policy) {
        ensureUsable();
        var key = validateAttributeName(name);
        var content = validateAttributeValue(value);
        var configured = Objects.requireNonNull(policy, "policy");
        var proposed = new LinkedHashMap<>(attributes);
        proposed.put(key, content);
        validateAttributes(proposed, configured);
        attributes.clear();
        attributes.putAll(proposed);
        return this;
    }

    /** Removes one attribute, if it exists. */
    public synchronized Session remove(String name) {
        ensureUsable();
        attributes.remove(validateAttributeName(name));
        return this;
    }

    /** Marks this session for deletion; a manager/store must not persist it again. */
    public synchronized void invalidate() {
        invalidated = true;
        attributes.clear();
    }

    /** Returns whether this session was explicitly invalidated. */
    public synchronized boolean isInvalidated() {
        return invalidated;
    }

    /** Returns whether invalidation or expiry makes this session unusable at {@code now}. */
    public synchronized boolean isExpired(Instant now) {
        return invalidated || !Objects.requireNonNull(now, "now").isBefore(expiresAt);
    }

    /** Advances idle expiry without extending the absolute expiry. */
    public synchronized void touch(Instant now, SessionPolicy policy) {
        ensureUsable();
        var effectiveNow = laterOf(Objects.requireNonNull(now, "now"), lastAccessedAt);
        if (!effectiveNow.isBefore(expiresAt)) {
            invalidated = true;
            attributes.clear();
            return;
        }
        lastAccessedAt = effectiveNow;
        expiresAt = expiry(createdAt, effectiveNow, Objects.requireNonNull(policy, "policy"));
    }

    /** Returns whether this policy requires an identifier rotation at {@code now}. */
    public synchronized boolean shouldRotate(Instant now, SessionPolicy policy) {
        ensureUsable();
        var configured = Objects.requireNonNull(policy, "policy");
        if (configured.rotationDisabled()) {
            return false;
        }
        return !Objects.requireNonNull(now, "now").isBefore(plusSaturated(rotatedAt, configured.rotationInterval()));
    }

    /** Replaces the opaque identifier while preserving state and absolute lifetime. */
    public synchronized SessionId rotate(SessionId replacement, Instant now, SessionPolicy policy) {
        ensureUsable();
        var next = Objects.requireNonNull(replacement, "replacement");
        if (next.equals(id)) {
            throw new IllegalArgumentException("a rotated session ID must differ from the current ID");
        }
        touch(now, policy);
        ensureUsable();
        var previous = id;
        id = next;
        rotatedAt = laterOf(Objects.requireNonNull(now, "now"), rotatedAt);
        return previous;
    }

    synchronized Session snapshot() {
        return new Session(id, attributes, createdAt, lastAccessedAt, expiresAt, rotatedAt, invalidated);
    }

    private void ensureUsable() {
        if (invalidated) {
            throw new IllegalStateException("session is invalidated");
        }
    }

    private static Instant expiry(Instant createdAt, Instant accessedAt, SessionPolicy policy) {
        return earlierOf(
                plusSaturated(createdAt, policy.absoluteTimeout()),
                plusSaturated(accessedAt, policy.idleTimeout()));
    }

    private static Instant plusSaturated(Instant instant, Duration duration) {
        try {
            return instant.plus(duration);
        } catch (DateTimeException | ArithmeticException ignored) {
            return Instant.MAX;
        }
    }

    private static Instant earlierOf(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static Instant laterOf(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private static String validateAttributeName(String name) {
        var candidate = Objects.requireNonNull(name, "name");
        if (candidate.isBlank() || candidate.indexOf('\0') >= 0 || candidate.indexOf('\r') >= 0 || candidate.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("session attribute name must be non-blank and contain no control delimiter");
        }
        return candidate;
    }

    private static String validateAttributeValue(String value) {
        var candidate = Objects.requireNonNull(value, "value");
        if (candidate.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("session attribute value must not contain NUL");
        }
        return candidate;
    }

    private static void validateAttributes(Map<String, String> attributes, SessionPolicy policy) {
        if (attributes.size() > policy.maximumAttributes()) {
            throw new SessionLimitExceededException("session attribute count", policy.maximumAttributes(), attributes.size());
        }
        long bytes = 0;
        for (var entry : attributes.entrySet()) {
            bytes = Math.addExact(bytes, entry.getKey().getBytes(StandardCharsets.UTF_8).length);
            bytes = Math.addExact(bytes, entry.getValue().getBytes(StandardCharsets.UTF_8).length);
            if (bytes > policy.maximumAttributeBytes()) {
                throw new SessionLimitExceededException(
                        "session attribute bytes", policy.maximumAttributeBytes(), bytes);
            }
        }
    }
}
