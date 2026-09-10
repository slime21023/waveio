package io.wavejava.wave.netty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

class Http1RequestFramingTest {
    @Test
    void acceptsOneUnambiguousHttp11Framing() {
        var request = request();
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "12");
        assertTrue(Http1RequestFraming.isSafe(request));

        var chunked = request();
        chunked.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        assertTrue(Http1RequestFraming.isSafe(chunked));
    }

    @Test
    void rejectsSmugglingPrimitivesBeforeApplicationAdmission() {
        var contentLengthAndTransferEncoding = request();
        contentLengthAndTransferEncoding.headers().set(HttpHeaderNames.CONTENT_LENGTH, "4");
        contentLengthAndTransferEncoding.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        assertFalse(Http1RequestFraming.isSafe(contentLengthAndTransferEncoding));

        var duplicateLength = request();
        duplicateLength.headers().add(HttpHeaderNames.CONTENT_LENGTH, "4");
        duplicateLength.headers().add(HttpHeaderNames.CONTENT_LENGTH, "4");
        assertFalse(Http1RequestFraming.isSafe(duplicateLength));

        var nonFinalTransferCoding = request();
        nonFinalTransferCoding.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "gzip, chunked");
        assertFalse(Http1RequestFraming.isSafe(nonFinalTransferCoding));

        var framingConnectionToken = request();
        framingConnectionToken.headers().set(HttpHeaderNames.CONNECTION, "keep-alive, transfer-encoding");
        assertFalse(Http1RequestFraming.isSafe(framingConnectionToken));

        var duplicateHost = request();
        duplicateHost.headers().add(HttpHeaderNames.HOST, "second.example");
        assertFalse(Http1RequestFraming.isSafe(duplicateHost));
    }

    @Test
    void rejectsMalformedLengthTransferCodingAndHostValues() {
        var negativeLength = request();
        negativeLength.headers().set(HttpHeaderNames.CONTENT_LENGTH, "-1");
        assertFalse(Http1RequestFraming.isSafe(negativeLength));

        var nonNumericLength = request();
        nonNumericLength.headers().set(HttpHeaderNames.CONTENT_LENGTH, "four");
        assertFalse(Http1RequestFraming.isSafe(nonNumericLength));

        var unsupportedTransferCoding = request();
        unsupportedTransferCoding.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "gzip");
        assertFalse(Http1RequestFraming.isSafe(unsupportedTransferCoding));

        var repeatedChunked = request();
        repeatedChunked.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked, chunked");
        assertFalse(Http1RequestFraming.isSafe(repeatedChunked));

        var missingHost = request();
        missingHost.headers().remove(HttpHeaderNames.HOST);
        assertFalse(Http1RequestFraming.isSafe(missingHost));
    }

    private static DefaultHttpRequest request() {
        var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        request.headers().set(HttpHeaderNames.HOST, "example.test");
        return request;
    }
}
