package io.waveio.server;

import io.waveio.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable exact-class renderer registry; it performs no assignable-type lookup. */
public final class Renderers {
    private final Map<Class<?>, Renderer<?>> renderers;
    private Renderers(Map<Class<?>, Renderer<?>> renderers) { this.renderers = Map.copyOf(renderers); }
    /** Creates an immutable exact-class registry. */
    public static Renderers of(List<? extends Renderer<?>> values) {
        Objects.requireNonNull(values, "values"); Map<Class<?>, Renderer<?>> entries = new LinkedHashMap<>();
        for (Renderer<?> renderer : values) { Renderer<?> candidate = Objects.requireNonNull(renderer, "renderer"); if (entries.putIfAbsent(Objects.requireNonNull(candidate.type(), "renderer type"), candidate) != null) { throw new IllegalArgumentException("duplicate renderer type"); } }
        return new Renderers(entries);
    }
    /** Renders only when the runtime class has an exact registered renderer. */
    public HttpResponse render(Object value) {
        Objects.requireNonNull(value, "value"); Renderer<?> renderer = renderers.get(value.getClass());
        if (renderer == null) { throw new IllegalArgumentException("no exact renderer for " + value.getClass().getName()); }
        return renderUnchecked(renderer, value);
    }
    @SuppressWarnings("unchecked") private static <T> HttpResponse renderUnchecked(Renderer<?> renderer, Object value) { return ((Renderer<T>) renderer).render((T) value); }
}
