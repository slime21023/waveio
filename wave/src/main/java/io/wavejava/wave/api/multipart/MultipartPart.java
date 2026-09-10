package io.wavejava.wave.api.multipart;

import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.MediaType;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/** One decoded part of a {@link MultipartForm}. */
public final class MultipartPart {
    private final String name;
    private final Headers headers;
    private final MediaType mediaType;
    private final byte[] content;
    private final Upload upload;
    private final long size;

    private MultipartPart(String name, Headers headers, MediaType mediaType, byte[] content, Upload upload, long size) {
        this.name = Objects.requireNonNull(name, "name");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.mediaType = mediaType;
        // Factories are package-private and receive a fresh parser-owned range copy. Keeping that
        // copy avoids a second full-size allocation while public accessors remain defensive.
        this.content = content;
        this.upload = upload;
        this.size = size;
    }

    static MultipartPart field(String name, Headers headers, MediaType mediaType, byte[] content) {
        return new MultipartPart(name, headers, mediaType, Objects.requireNonNull(content, "content"), null, content.length);
    }

    static MultipartPart upload(String name, Headers headers, MediaType mediaType, Upload upload) {
        return new MultipartPart(name, headers, mediaType, null, Objects.requireNonNull(upload, "upload"), upload.size());
    }

    /** Returns the {@code name} parameter from Content-Disposition. */
    public String name() {
        return name;
    }

    /** Returns immutable part headers. */
    public Headers headers() {
        return headers;
    }

    /** Returns the declared Content-Type, if present. */
    public Optional<MediaType> mediaType() {
        return Optional.ofNullable(mediaType);
    }

    /** Returns the content size in bytes. */
    public long size() {
        return size;
    }

    /** Returns whether this part is represented by a temporary {@link Upload}. */
    public boolean isUpload() {
        return upload != null;
    }

    /** Returns the temporary upload when this is a file part. */
    public Optional<Upload> upload() {
        return Optional.ofNullable(upload);
    }

    /** Returns a defensive byte copy for a non-file field part. */
    public byte[] bytes() {
        if (content == null) {
            throw new IllegalStateException("File parts must be read through upload()");
        }
        return content.clone();
    }

    /** Decodes a non-file field part as UTF-8. */
    public String text() {
        return text(StandardCharsets.UTF_8);
    }

    /** Decodes a non-file field part with {@code charset}. */
    public String text(Charset charset) {
        return new String(bytes(), Objects.requireNonNull(charset, "charset"));
    }
}
