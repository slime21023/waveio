package io.waveio.http.body;

/**
 * Converts between HTTP message bodies and application values.
 *
 * <p>WaveIO ships no implementation. The core needs only this seam, so object mapping stays out of
 * the runtime: no dependency, no scanning and no required reflection. Supply an implementation with
 * {@code HttpServerBuilder.bodyCodec(...)} to enable {@code HttpResponse.value(...)} on the way out.
 *
 * <p>Implementations must be safe for concurrent use: one codec serves every connection.
 */
public interface BodyCodec {

    /** Content type written for encoded values, for example {@code application/json}. */
    String contentType();

    /** Encodes an application value into body bytes. */
    byte[] encode(Object value) throws Exception;

    /** Decodes body bytes into the requested type. */
    <T> T decode(byte[] body, Class<T> type) throws Exception;
}
