package io.waveio.http.internal;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RequestTargetParser {
    private RequestTargetParser() {}

    public static Target parse(String value) {
        if (value == null || value.isEmpty()) throw invalid("Request target is empty");
        if (value.indexOf('#') >= 0) throw invalid("Request target must not contain a fragment");

        String rawPath;
        String rawQuery;
        if (value.startsWith("/")) {
            if (value.startsWith("//")) throw invalid("Authority-form request target is unsupported");
            int queryStart = value.indexOf('?');
            rawPath = queryStart < 0 ? value : value.substring(0, queryStart);
            rawQuery = queryStart < 0 ? null : value.substring(queryStart + 1);
        } else {
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException failure) {
                throw invalid("Malformed absolute request target", failure);
            }
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.isOpaque() || uri.getRawAuthority() == null || uri.getRawAuthority().isBlank()
                    || uri.getRawUserInfo() != null) {
                throw invalid("Unsupported request target form");
            }
            rawPath = uri.getRawPath();
            if (rawPath == null || rawPath.isEmpty()) rawPath = "/";
            rawQuery = uri.getRawQuery();
        }

        return new Target(decodePath(rawPath), decodeQuery(rawQuery));
    }

    private static String decodePath(String rawPath) {
        if (!rawPath.startsWith("/")) throw invalid("Request path must start with '/'");
        if (rawPath.indexOf('\\') >= 0) throw invalid("Request path must not contain backslashes");
        if (rawPath.equals("/")) return rawPath;

        String[] rawSegments = rawPath.substring(1).split("/", -1);
        var decoded = new ArrayList<String>(rawSegments.length);
        for (int index = 0; index < rawSegments.length; index++) {
            String segment = decodeComponent(rawSegments[index], false);
            boolean trailing = index == rawSegments.length - 1 && segment.isEmpty();
            if (segment.isEmpty() && !trailing) throw invalid("Request path contains an empty segment");
            if (segment.equals(".") || segment.equals("..")) {
                throw invalid("Request path contains a dot segment");
            }
            if (segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0) {
                throw invalid("Encoded path separators are not allowed");
            }
            rejectControlCharacters(segment);
            decoded.add(segment);
        }
        return "/" + String.join("/", decoded);
    }

    private static Map<String, List<String>> decodeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, List<String>>();
        for (String entry : rawQuery.split("&", -1)) {
            int separator = entry.indexOf('=');
            String rawName = separator < 0 ? entry : entry.substring(0, separator);
            String rawValue = separator < 0 ? "" : entry.substring(separator + 1);
            String name = decodeComponent(rawName, true);
            String value = decodeComponent(rawValue, true);
            rejectControlCharacters(name);
            rejectControlCharacters(value);
            result.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        var immutable = new LinkedHashMap<String, List<String>>();
        result.forEach((name, values) -> immutable.put(name, List.copyOf(values)));
        return Map.copyOf(immutable);
    }

    private static String decodeComponent(String value, boolean plusAsSpace) {
        var result = new StringBuilder(value.length());
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            if (current == '%' ) {
                var bytes = new ByteArrayOutputStream();
                while (index < value.length() && value.charAt(index) == '%') {
                    if (index + 2 >= value.length()) throw invalid("Incomplete percent escape");
                    int high = Character.digit(value.charAt(index + 1), 16);
                    int low = Character.digit(value.charAt(index + 2), 16);
                    if (high < 0 || low < 0) throw invalid("Invalid percent escape");
                    bytes.write((high << 4) | low);
                    index += 3;
                }
                result.append(decodeUtf8(bytes.toByteArray()));
            } else {
                result.append(plusAsSpace && current == '+' ? ' ' : current);
                index++;
            }
        }
        return result.toString();
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw invalid("Percent escapes are not valid UTF-8", failure);
        }
    }

    private static void rejectControlCharacters(String value) {
        if (value.codePoints().anyMatch(codePoint -> codePoint <= 0x1f || codePoint == 0x7f)) {
            throw invalid("Request target contains a control character");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException invalid(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }

    public record Target(String path, Map<String, List<String>> queryParameters) {
        public Target { queryParameters = Map.copyOf(queryParameters); }
    }
}
