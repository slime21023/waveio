package io.waveio.http;

import io.waveio.registry.Registry;
import io.waveio.task.Task;
import java.util.List;
import java.util.Objects;

/** Internal request context implementation shared by in-memory and transport fixtures. */
final class DefaultContext implements Context {
    private final HttpRequest request; private final Registry registry; private final Chain chain; private final ResponseTransaction response; private final java.util.Map<String, String> parameters;
    private boolean controlFlowSelected;
    DefaultContext(HttpRequest request, Registry registry, Chain chain, ResponseTransaction response) { this(request, registry, chain, response, java.util.Map.of()); }
    private DefaultContext(HttpRequest request, Registry registry, Chain chain, ResponseTransaction response, java.util.Map<String, String> parameters) { this.request = Objects.requireNonNull(request, "request"); this.registry = Objects.requireNonNull(registry, "registry"); this.chain = Objects.requireNonNull(chain, "chain"); this.response = Objects.requireNonNull(response, "response"); this.parameters = java.util.Map.copyOf(parameters); }
    public HttpRequest request() { return request; }
    public Body body() { return request.body(); }
    public Registry registry() { return registry; }
    public java.util.Map<String, String> pathParameters() { return parameters; }
    public void respond(HttpResponse value) { select(); response.commit(value); }
    public Task<Void> next() { select(); return chain.next(this); }
    public Task<Void> insert(List<Handler> handlers) { select(); return chain.insert(this, List.copyOf(handlers)); }
    DefaultContext withChain(Chain nextChain) { return new DefaultContext(request, registry, nextChain, response, parameters); }
    DefaultContext withParameters(java.util.Map<String, String> values) { return new DefaultContext(request, registry, chain, response, values); }
    private void select() { if (controlFlowSelected) { throw new IllegalStateException("handler invocation already selected a control flow"); } controlFlowSelected = true; }
}
