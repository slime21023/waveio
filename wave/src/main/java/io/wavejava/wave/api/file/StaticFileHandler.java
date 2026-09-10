package io.wavejava.wave.api.file;

import io.wavejava.wave.api.http.Problem;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;
import io.wavejava.wave.api.routing.Handler;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A root-contained, bounded static-file handler.
 *
 * <p>Resolve a route such as {@code /assets/{*path}} with {@link Builder#pathParameter(String)}.
 * The handler rejects absolute paths, backslash paths, dot-segment escapes, and symlink targets
 * outside its real root before any file bytes are read.</p>
 */
public final class StaticFileHandler implements Handler {
    private final Path root;
    private final String pathParameter;
    private final int maximumBytes;

    private StaticFileHandler(Builder builder) {
        try {
            root = Objects.requireNonNull(builder.root, "root").toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Static file root must exist and be readable: " + builder.root, failure);
        }
        if (!java.nio.file.Files.isDirectory(root)) {
            throw new IllegalArgumentException("Static file root must be a directory: " + root);
        }
        pathParameter = builder.pathParameter;
        maximumBytes = builder.maximumBytes;
    }

    /** Starts configuration of a handler rooted at one existing directory. */
    public static Builder builder(Path root) {
        return new Builder(root);
    }

    /** Creates a handler that maps the request path without its leading slash below {@code root}. */
    public static StaticFileHandler from(Path root) {
        return builder(root).build();
    }

    /** Returns the canonical directory constraining this handler. */
    public Path root() {
        return root;
    }

    @Override
    public void handle(Request request, Response response) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        try {
            var file = resolve(relativePath(request));
            FileResponse.builder(file).maximumBytes(maximumBytes).build().writeTo(request, response);
        } catch (NoSuchFileException missing) {
            response.problem(Problem.of(404, "Not Found"));
        } catch (AccessDeniedException | InvalidPathException denied) {
            response.problem(Problem.of(403, "Forbidden"));
        }
    }

    private String relativePath(Request request) {
        if (pathParameter != null) {
            return request.pathParameter(pathParameter).orElse("");
        }
        return request.path().length() == 1 ? "" : request.path().substring(1);
    }

    private Path resolve(String relative) throws IOException {
        if (relative.isEmpty() || relative.startsWith("/") || relative.indexOf('\\') >= 0 || relative.indexOf('\u0000') >= 0) {
            throw new AccessDeniedException(relative);
        }
        final Path requested;
        try {
            requested = Path.of(relative);
        } catch (InvalidPathException failure) {
            throw new AccessDeniedException(relative, null, "invalid static-file path");
        }
        if (requested.isAbsolute()) {
            throw new AccessDeniedException(relative);
        }
        var candidate = root.resolve(requested).normalize();
        if (!candidate.startsWith(root)) {
            throw new AccessDeniedException(relative);
        }
        var real = candidate.toRealPath();
        if (!real.startsWith(root)) {
            throw new AccessDeniedException(relative);
        }
        return real;
    }

    /** Builder for a root-contained {@link StaticFileHandler}. */
    public static final class Builder {
        private final Path root;
        private String pathParameter;
        private int maximumBytes = FileResponse.DEFAULT_MAXIMUM_BYTES;

        private Builder(Path root) {
            this.root = Objects.requireNonNull(root, "root");
        }

        /** Reads the relative file path from this named route parameter. */
        public Builder pathParameter(String pathParameter) {
            Objects.requireNonNull(pathParameter, "pathParameter");
            if (pathParameter.isBlank()) {
                throw new IllegalArgumentException("pathParameter must not be blank");
            }
            this.pathParameter = pathParameter;
            return this;
        }

        /** Sets the maximum selected file bytes held in one aggregated response. */
        public Builder maximumBytes(long maximumBytes) {
            if (maximumBytes <= 0 || maximumBytes > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("maximumBytes must be between 1 and " + Integer.MAX_VALUE);
            }
            this.maximumBytes = (int) maximumBytes;
            return this;
        }

        /** Creates a root-contained static-file handler. */
        public StaticFileHandler build() {
            return new StaticFileHandler(this);
        }
    }
}
