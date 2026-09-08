package io.waveio.http;

import io.waveio.task.Task;
import java.util.List;

/** Represents the remaining handlers for an HTTP context. */
public interface Chain {
    /** Delegates to the next handler. */ Task<Void> next(Context context);
    /** Inserts a local handler sequence before the remaining chain. */ Task<Void> insert(Context context, List<Handler> handlers);
}
