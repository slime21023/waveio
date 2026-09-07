package io.waveio.http;

import io.waveio.registry.Registry;
import io.waveio.task.Task;
import java.util.List;

/** The request-scoped state provided to a handler. */
public interface Context {
    /** Returns immutable request metadata. */ HttpRequest request();
    /** Returns this request's registry view. */ Registry registry();
    /** Commits the single response for this invocation. */ void respond(HttpResponse response);
    /** Delegates to the next handler. */ Task<Void> next();
    /** Inserts handlers as a child chain. */ Task<Void> insert(List<Handler> handlers);
}
