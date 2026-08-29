package io.waveio.http;

/**
 * Standard HTTP request methods defined by RFC 7231 and related specifications.
 */
public enum HttpMethod {
    /** HTTP GET */
    GET,
    /** HTTP HEAD */
    HEAD,
    /** HTTP POST */
    POST,
    /** HTTP PUT */
    PUT,
    /** HTTP PATCH */
    PATCH,
    /** HTTP DELETE */
    DELETE,
    /** HTTP OPTIONS */
    OPTIONS,
    /** HTTP TRACE */
    TRACE,
    /** HTTP CONNECT */
    CONNECT
}
