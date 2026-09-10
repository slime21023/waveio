package io.wavejava.wave.api.http;

/** Maps one application exception to a response before it is committed. */
@FunctionalInterface
public interface ExceptionMapper<T extends Throwable> {
    /**
     * Writes a response for {@code exception}.
     *
     * @throws Exception when mapping itself cannot complete; the runtime then uses its fallback response
     */
    void map(T exception, Request request, Response response) throws Exception;
}
