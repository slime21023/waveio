package io.waveio.http.internal.files;

import io.waveio.http.HttpRequest;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.http.handler.HttpHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Serves files from one directory.
 *
 * <p>Containment is the point of this class, so it is internal: an application cannot assemble a
 * subtly weaker version out of exposed parts. Request targets are already canonical by the time
 * routing happens — dot segments, encoded slashes, backslashes and invalid UTF-8 are rejected while
 * parsing the request target — so what remains here is what that layer cannot see: resolution that
 * leaves the root through an absolute-looking segment or a symbolic link.
 */
public final class StaticFiles {
    private static final System.Logger LOG = System.getLogger(StaticFiles.class.getName());

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    /** Deterministic by design: {@code Files.probeContentType} varies by platform. */
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("css", "text/css"),
            Map.entry("csv", "text/csv"),
            Map.entry("gif", "image/gif"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("ico", "image/vnd.microsoft.icon"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("js", "text/javascript"),
            Map.entry("json", "application/json"),
            Map.entry("map", "application/json"),
            Map.entry("mjs", "text/javascript"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("png", "image/png"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("wasm", "application/wasm"),
            Map.entry("webp", "image/webp"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("xml", "application/xml"));

    private StaticFiles() {}

    /**
     * Builds a handler serving {@code root}. The handler performs blocking file-system calls, so
     * it must be registered as a blocking route.
     */
    public static HttpHandler handler(Path root, String parameter) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(parameter, "parameter");
        Path canonicalRoot = canonical(root);
        if (canonicalRoot == null) {
            throw new IllegalArgumentException("Static file root is not a readable directory: "
                    + root);
        }
        return request -> serve(canonicalRoot, request.pathParam(parameter).orElse(""), request);
    }

    private static HttpResponse serve(Path root, String relative, HttpRequest request) {
        Path file = resolveWithin(root, relative);
        if (file == null) return notFound();

        long modifiedMillis;
        try {
            modifiedMillis = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.DEBUG,
                    () -> "Cannot read the modification time of " + file, failure);
            return notFound();
        }
        // HTTP dates have one-second resolution, so compare at that granularity.
        long modifiedSeconds = modifiedMillis / 1000;
        String lastModified = HTTP_DATE.format(
                ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(modifiedSeconds),
                        ZoneOffset.UTC));

        if (notModifiedSince(request, modifiedSeconds)) {
            return HttpResponse.status(HttpStatus.NOT_MODIFIED)
                    .header("last-modified", lastModified)
                    .build();
        }
        return HttpResponse.status(HttpStatus.OK)
                .header("content-type", contentType(file))
                .header("last-modified", lastModified)
                .file(file);
    }

    /**
     * Resolves a request remainder inside the root, or returns {@code null} when the result is not
     * a readable regular file contained by it.
     *
     * <p>{@code Path.resolve} discards the root entirely when given an absolute-looking segment
     * (notably a Windows drive prefix), and a symbolic link can point anywhere, so containment is
     * checked against the real path rather than the constructed one.
     */
    private static Path resolveWithin(Path root, String relative) {
        if (relative.isEmpty()) return null;
        Path candidate;
        try {
            candidate = root.resolve(relative).normalize();
        } catch (InvalidPathException failure) {
            return null;
        }
        if (!candidate.startsWith(root)) return null;
        Path real = canonical(candidate);
        if (real == null || !real.startsWith(root)) return null;
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) return null;
        return Files.isReadable(real) ? real : null;
    }

    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | InvalidPathException failure) {
            return null;
        }
    }

    private static boolean notModifiedSince(HttpRequest request, long modifiedSeconds) {
        var header = request.headers().first("if-modified-since");
        if (header.isEmpty()) return false;
        try {
            long since = ZonedDateTime.parse(header.get(), HTTP_DATE).toEpochSecond();
            return modifiedSeconds <= since;
        } catch (DateTimeParseException failure) {
            return false;
        }
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "application/octet-stream";
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private static HttpResponse notFound() {
        return HttpResponse.status(HttpStatus.NOT_FOUND)
                .header("content-type", "text/plain; charset=utf-8")
                .body("Not Found");
    }
}
