package io.wavejava.wave.api.render;

import io.wavejava.wave.api.http.Body;
import io.wavejava.wave.api.http.MediaType;
import java.util.Objects;

/**
 * Converts one bounded request {@link Body} to a typed value.
 *
 * <p>A parser must not retain the body or attempt to consume it more than once. Callers select a
 * parser by {@link #supports(ParseTarget, MediaType)} before invoking it.</p>
 *
 * @param <T> the parsed value type
 */
public interface Parser<T> {
    /** Returns whether this parser accepts {@code target} for the supplied request media type. */
    boolean supports(ParseTarget<T> target, MediaType mediaType);

    /** Parses the body using this parser's default decoding rules. */
    T parse(Body body, ParseTarget<T> target) throws Exception;

    /**
     * Parses the body with its declared media type.
     *
     * <p>The default implementation preserves the two-argument contract while checking selection
     * first. Parsers that need media-type parameters, such as a declared charset, may override
     * this method.</p>
     */
    default T parse(Body body, ParseTarget<T> target, MediaType mediaType) throws Exception {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(mediaType, "mediaType");
        if (!supports(target, mediaType)) {
            throw new IllegalArgumentException("Parser does not support " + target + " from " + mediaType);
        }
        return parse(body, target);
    }
}
