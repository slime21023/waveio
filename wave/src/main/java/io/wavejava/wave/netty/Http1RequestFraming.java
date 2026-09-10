package io.wavejava.wave.netty;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.util.List;
import java.util.Locale;

/** Strict HTTP/1.1 request-framing validation used before application admission. */
final class Http1RequestFraming {
    private Http1RequestFraming() {
    }

    /**
     * Rejects ambiguous request framing rather than attempting proxy-specific recovery.
     *
     * <p>Netty's decoder remains the first syntax defense. This guard adds Wave's fail-closed
     * policy for duplicate Content-Length, any Content-Length plus Transfer-Encoding, non-final or
     * unsupported transfer coding, ambiguous Host, and framing-header connection tokens.</p>
     */
    static boolean isSafe(HttpRequest request) {
        if (!HttpVersion.HTTP_1_1.equals(request.protocolVersion())) {
            return true;
        }
        var headers = request.headers();
        if (headers.getAll(HttpHeaderNames.HOST).size() != 1 || headers.get(HttpHeaderNames.HOST).isBlank()) {
            return false;
        }
        var contentLengths = headers.getAll(HttpHeaderNames.CONTENT_LENGTH);
        if (contentLengths.size() > 1 || (!contentLengths.isEmpty() && !isCanonicalContentLength(contentLengths.getFirst()))) {
            return false;
        }
        var transferEncodings = headers.getAll(HttpHeaderNames.TRANSFER_ENCODING);
        if (!transferEncodings.isEmpty()) {
            if (!contentLengths.isEmpty() || transferEncodings.size() != 1 || !isExactlyChunked(transferEncodings.getFirst())) {
                return false;
            }
        }
        return connectionHeaderDoesNotNameFraming(headers.getAll(HttpHeaderNames.CONNECTION));
    }

    private static boolean isCanonicalContentLength(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (var index = 0; index < value.length(); index++) {
            if (value.charAt(index) < '0' || value.charAt(index) > '9') {
                return false;
            }
        }
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isExactlyChunked(String value) {
        return value != null && value.trim().equalsIgnoreCase("chunked") && value.indexOf(',') < 0;
    }

    private static boolean connectionHeaderDoesNotNameFraming(List<String> values) {
        for (var value : values) {
            for (var token : value.split(",", -1)) {
                var normalized = token.trim().toLowerCase(Locale.ROOT);
                if (normalized.equals("content-length") || normalized.equals("transfer-encoding") || normalized.equals("host")) {
                    return false;
                }
            }
        }
        return true;
    }
}
