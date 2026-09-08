package io.waveio.http;

import io.waveio.task.Task;
import java.util.Objects;

/** A handler that dispatches a request through an immutable route table. */
public final class Router implements Handler {
    private final RouteTable routes;
    /** Creates a router from an immutable table. */ public Router(RouteTable routes) { this.routes = Objects.requireNonNull(routes, "routes"); }
    /** Dispatches matched route handlers or commits the appropriate policy response. */ @Override public Task<Void> handle(Context context) {
        RouteTable.Resolution resolution = routes.resolve(context.request().method(), context.request().uri().path());
        if (!resolution.matched()) { context.respond(RoutingResponses.forResolution(resolution)); return Task.success(null); }
        return resolution.route().handler().handle(((DefaultContext) context).withParameters(resolution.parameters()));
    }
}
