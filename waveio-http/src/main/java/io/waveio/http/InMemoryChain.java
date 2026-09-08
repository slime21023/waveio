package io.waveio.http;

import io.waveio.task.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** In-memory chain implementation for handler fixtures. */
final class InMemoryChain implements Chain {
    private final List<Handler> handlers;
    private final int index;
    InMemoryChain(List<Handler> handlers) { this(handlers, 0); }
    private InMemoryChain(List<Handler> handlers, int index) { this.handlers = List.copyOf(handlers); this.index = index; }
    public Task<Void> next(Context context) {
        DefaultContext current = requireContext(context);
        if (index == handlers.size()) { return Task.success(null); }
        return handlers.get(index).handle(current.withChain(new InMemoryChain(handlers, index + 1)));
    }
    public Task<Void> insert(Context context, List<Handler> inserted) {
        DefaultContext current = requireContext(context); List<Handler> combined = new ArrayList<>(inserted); combined.addAll(handlers.subList(index, handlers.size()));
        return new InMemoryChain(combined).next(current);
    }
    private static DefaultContext requireContext(Context context) { return (DefaultContext) Objects.requireNonNull(context, "context"); }
}
