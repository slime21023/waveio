package io.waveio.http;

import java.util.Objects;

/** Creates transport-independent responses for route policy outcomes. */
public final class RoutingResponses {
    private RoutingResponses() { }
    /** Converts a non-matched routing result into its required response. */
    public static HttpResponse forResolution(RouteTable.Resolution resolution) {
        Objects.requireNonNull(resolution, "resolution");
        if (resolution.matched()) { throw new IllegalArgumentException("matched route has no policy response"); }
        if (resolution.status() == HttpStatus.METHOD_NOT_ALLOWED) {
            return new HttpResponse(HttpStatus.METHOD_NOT_ALLOWED, Headers.builder().add("Allow", resolution.allowHeader()).build());
        }
        return HttpResponse.of(HttpStatus.NOT_FOUND);
    }
}
