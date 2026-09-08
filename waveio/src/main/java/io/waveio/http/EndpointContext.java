package io.waveio.http;

import io.waveio.registry.Registry;
import java.util.Map;

/** Read-only request state available to a high-level endpoint. */
public interface EndpointContext {
    /** Returns immutable request metadata. */
    HttpRequest request();
    /** Returns the single-consumption request body. */
    Body body();
    /** Returns this request's registry view. */
    Registry registry();
    /** Returns immutable path parameters. */
    Map<String, String> pathParameters();
}
