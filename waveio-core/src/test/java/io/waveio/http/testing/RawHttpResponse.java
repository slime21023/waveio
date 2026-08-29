package io.waveio.http.testing;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Minimal, framing-aware HTTP/1.1 response reader for real-socket tests. */
public record RawHttpResponse(String statusLine, Map<String, List<String>> headers,
        byte[] body, String wireHeaders) {
    public RawHttpResponse {
        headers = Map.copyOf(headers);
        body = body.clone();
    }

    public static RawHttpResponse read(Socket socket) throws IOException {
        return read(socket.getInputStream());
    }

    static RawHttpResponse read(java.io.InputStream input) throws IOException {
        String wireHeaders = readHeaders(input);
        var lines = wireHeaders.substring(0, wireHeaders.length() - 4).split("\\r\\n");
        var headers = new LinkedHashMap<String, List<String>>();
        for (int index = 1; index < lines.length; index++) {
            int separator = lines[index].indexOf(':');
            if (separator <= 0) continue;
            String name = lines[index].substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = lines[index].substring(separator + 1).trim();
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        byte[] body;
        if (header(headers, "transfer-encoding").map(value -> value.equalsIgnoreCase("chunked"))
                .orElse(false)) {
            body = readChunked(input);
        } else {
            int length = header(headers, "content-length").map(Integer::parseInt).orElse(0);
            body = input.readNBytes(length);
            if (body.length != length) throw new EOFException("Response body ended early");
        }
        return new RawHttpResponse(lines[0], headers, body, wireHeaders);
    }

    public static String readWireHeaders(Socket socket) throws IOException {
        return readHeaders(socket.getInputStream());
    }

    public int status() {
        String[] parts = statusLine.split(" ", 3);
        return Integer.parseInt(parts[1]);
    }

    public Optional<String> header(String name) {
        return header(headers, name);
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public String wireText() {
        return wireHeaders + bodyText();
    }

    private static Optional<String> header(Map<String, List<String>> headers, String name) {
        var values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    private static String readHeaders(java.io.InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int value = input.read();
            if (value < 0) throw new EOFException("Response headers ended early");
            bytes.write(value);
            int expected = switch (matched) {
                case 0, 2 -> '\r';
                case 1, 3 -> '\n';
                default -> -1;
            };
            matched = value == expected ? matched + 1 : value == '\r' ? 1 : 0;
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    private static byte[] readChunked(java.io.InputStream input) throws IOException {
        var body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(input);
            int separator = sizeLine.indexOf(';');
            String sizeToken = separator < 0 ? sizeLine : sizeLine.substring(0, separator);
            int size = Integer.parseInt(sizeToken.trim(), 16);
            if (size == 0) {
                while (!readLine(input).isEmpty()) {}
                return body.toByteArray();
            }
            byte[] chunk = input.readNBytes(size);
            if (chunk.length != size) throw new EOFException("Response chunk ended early");
            body.writeBytes(chunk);
            if (!readLine(input).isEmpty()) throw new IOException("Missing chunk delimiter");
        }
    }

    private static String readLine(java.io.InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int value = input.read();
            if (value < 0) throw new EOFException("HTTP line ended early");
            if (previous == '\r' && value == '\n') {
                byte[] raw = bytes.toByteArray();
                return new String(raw, 0, raw.length - 1, StandardCharsets.US_ASCII);
            }
            bytes.write(value);
            previous = value;
        }
    }
}
