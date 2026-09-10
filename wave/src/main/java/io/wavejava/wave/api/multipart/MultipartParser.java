package io.wavejava.wave.api.multipart;

import io.wavejava.wave.api.http.CancellationToken;
import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.MediaType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded, incremental parser for {@code multipart/form-data} request streams.
 *
 * <p>The parser requests one buffer at a time. It retains only one part's bounded headers and a
 * boundary-sized lookbehind. File parts are written incrementally to parser-owned temporary files;
 * ordinary fields are deliberately aggregated only up to {@link Limits#maximumPartBytes()}. Call
 * {@link MultipartForm#close()} after application processing to remove every temporary upload.</p>
 *
 * <p>The parser does not choose an executor. A transport integration must invoke it from the
 * application execution boundary rather than a transport EventLoop.</p>
 */
public final class MultipartParser {
    /** Default finite budgets for an independently parsed multipart request. */
    public static final Limits DEFAULT_LIMITS = Limits.builder().build();

    private static final Path DEFAULT_TEMPORARY_DIRECTORY = Path.of(
            System.getProperty("java.io.tmpdir", "."), "wave");
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] HEADER_TERMINATOR = {'\r', '\n', '\r', '\n'};
    private static final int[] HEADER_TERMINATOR_FAILURE = {0, 0, 1, 2};
    private static final int FILE_WRITE_BUFFER_BYTES = 8 * 1024;

    private final Limits limits;
    private final Path temporaryDirectory;

    /** Creates a parser with {@link #DEFAULT_LIMITS} and a private child under the system temp directory. */
    public MultipartParser() {
        this(DEFAULT_LIMITS, DEFAULT_TEMPORARY_DIRECTORY);
    }

    /** Creates a parser with explicit finite limits and the system temporary directory. */
    public MultipartParser(Limits limits) {
        this(limits, DEFAULT_TEMPORARY_DIRECTORY);
    }

    private MultipartParser(Limits limits, Path temporaryDirectory) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.temporaryDirectory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
    }

    /** Starts configuration for a parser with explicit limits and temporary-file location. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the immutable parser limits. */
    public Limits limits() {
        return limits;
    }

    /** Returns the base directory under which per-form temporary directories are created. */
    public Path temporaryDirectory() {
        return temporaryDirectory;
    }

    /** Parses a multipart stream without a request cancellation token. */
    public CompletionStage<MultipartForm> parse(Flow.Publisher<ByteBuffer> body, MediaType contentType) {
        return begin(body, contentType, null).completion();
    }

    /**
     * Parses a multipart stream while cooperatively observing {@code cancellationToken}.
     *
     * <p>The operation registers with the token, so a disconnect or deadline immediately cancels
     * upstream demand and removes any temporary upload even when the publisher has stopped
     * producing buffers. The token is also checked before demand, for every received buffer, and
     * at part boundaries.</p>
     */
    public CompletionStage<MultipartForm> parse(
            Flow.Publisher<ByteBuffer> body,
            MediaType contentType,
            CancellationToken cancellationToken
    ) {
        return begin(body, contentType, cancellationToken).completion();
    }

    /**
     * Begins parsing and returns an operation that can cancel the upstream subscription directly.
     */
    public ParseOperation begin(
            Flow.Publisher<ByteBuffer> body,
            MediaType contentType,
            CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(contentType, "contentType");

        final Boundary boundary;
        try {
            boundary = Boundary.from(contentType);
        } catch (RuntimeException failure) {
            return ParseOperation.failed(failure);
        }

        var operation = new ParseOperation(this, boundary, cancellationToken);
        try {
            body.subscribe(operation.subscriber());
        } catch (Throwable failure) {
            operation.failFromPublisher(failure);
        }
        return operation;
    }

    private static Headers parseHeaders(byte[] source, int start, int end) {
        var headerBlock = new String(source, start, end - start, StandardCharsets.ISO_8859_1);
        var builder = Headers.builder();
        for (var line : headerBlock.split("\\r\\n", -1)) {
            if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new MultipartParseException("Multipart header line is empty or folded");
            }
            var separator = line.indexOf(':');
            if (separator <= 0) {
                throw new MultipartParseException("Multipart header is missing a name/value separator");
            }
            try {
                builder.add(line.substring(0, separator), trimOptionalWhitespace(line.substring(separator + 1)));
            } catch (IllegalArgumentException invalid) {
                throw new MultipartParseException("Multipart header is invalid", invalid);
            }
        }
        return builder.build();
    }

    private static Disposition parseDisposition(Headers headers) {
        var values = headers.all("Content-Disposition");
        if (values.size() != 1) {
            throw new MultipartParseException("Multipart part requires exactly one Content-Disposition header");
        }
        var pieces = splitDisposition(values.getFirst());
        if (pieces.isEmpty() || !pieces.getFirst().trim().equalsIgnoreCase("form-data")) {
            throw new MultipartParseException("Multipart Content-Disposition must be form-data");
        }
        var parameters = new LinkedHashMap<String, String>();
        for (var index = 1; index < pieces.size(); index++) {
            var piece = pieces.get(index).trim();
            var separator = piece.indexOf('=');
            if (separator <= 0) {
                throw new MultipartParseException("Malformed Content-Disposition parameter: " + piece);
            }
            var name = piece.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            var value = unquoteDispositionValue(piece.substring(separator + 1).trim());
            if (parameters.putIfAbsent(name, value) != null) {
                throw new MultipartParseException("Duplicate Content-Disposition parameter: " + name);
            }
        }
        var name = parameters.get("name");
        if (name == null || name.isBlank()) {
            throw new MultipartParseException("Multipart Content-Disposition requires a non-blank name parameter");
        }
        if (parameters.containsKey("filename*") && !parameters.containsKey("filename")) {
            throw new MultipartParseException("Multipart filename* is not supported without filename");
        }
        return new Disposition(name, Optional.ofNullable(parameters.get("filename")));
    }

    private static Optional<MediaType> parsePartMediaType(Headers headers) {
        var values = headers.all("Content-Type");
        if (values.isEmpty()) {
            return Optional.empty();
        }
        if (values.size() != 1) {
            throw new MultipartParseException("Multipart part has multiple Content-Type headers");
        }
        try {
            return Optional.of(MediaType.parse(values.getFirst()));
        } catch (IllegalArgumentException invalid) {
            throw new MultipartParseException("Multipart part Content-Type is invalid", invalid);
        }
    }

    private static List<String> splitDisposition(String value) {
        var pieces = new ArrayList<String>();
        var current = new StringBuilder();
        var quoted = false;
        var escaped = false;
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (quoted && character == '\\') {
                current.append(character);
                escaped = true;
            } else if (character == '"') {
                current.append(character);
                quoted = !quoted;
            } else if (character == ';' && !quoted) {
                addDispositionPiece(pieces, current, value);
            } else {
                current.append(character);
            }
        }
        if (quoted || escaped) {
            throw new MultipartParseException("Unterminated quoted Content-Disposition value");
        }
        addDispositionPiece(pieces, current, value);
        return pieces;
    }

    private static void addDispositionPiece(List<String> pieces, StringBuilder current, String source) {
        var piece = current.toString().trim();
        if (piece.isEmpty()) {
            throw new MultipartParseException("Empty Content-Disposition component: " + source);
        }
        pieces.add(piece);
        current.setLength(0);
    }

    private static String unquoteDispositionValue(String value) {
        if (value.isEmpty()) {
            throw new MultipartParseException("Empty Content-Disposition parameter value");
        }
        var result = new StringBuilder(value.length());
        if (value.charAt(0) == '"') {
            if (value.length() < 2 || value.charAt(value.length() - 1) != '"') {
                throw new MultipartParseException("Unterminated quoted Content-Disposition parameter");
            }
            var escaped = false;
            for (var index = 1; index < value.length() - 1; index++) {
                var character = value.charAt(index);
                if (escaped) {
                    result.append(character);
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else {
                    result.append(character);
                }
            }
            if (escaped) {
                throw new MultipartParseException("Invalid quoted Content-Disposition escape");
            }
        } else {
            result.append(value);
        }
        var decoded = result.toString();
        if (decoded.indexOf('\r') >= 0 || decoded.indexOf('\n') >= 0 || decoded.indexOf('\u0000') >= 0) {
            throw new MultipartParseException("Content-Disposition parameter contains an unsafe character");
        }
        return decoded;
    }

    private static String trimOptionalWhitespace(String value) {
        var start = 0;
        var end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) {
            end--;
        }
        return value.substring(start, end);
    }

    private record Disposition(String name, Optional<String> filename) {
    }

    private record Boundary(byte[] opening, byte[] following) {
        private static Boundary from(MediaType contentType) {
            if (!contentType.type().equals("multipart") || !contentType.subtype().equals("form-data")) {
                throw new MultipartParseException("Expected multipart/form-data but was " + contentType);
            }
            var value = contentType.parameter("boundary")
                    .orElseThrow(() -> new MultipartParseException("multipart/form-data requires a boundary parameter"));
            if (value.isEmpty() || value.length() > 70) {
                throw new MultipartParseException("Multipart boundary length must be between 1 and 70 characters");
            }
            for (var index = 0; index < value.length(); index++) {
                if (!isBoundaryCharacter(value.charAt(index))) {
                    throw new MultipartParseException("Multipart boundary contains an invalid character");
                }
            }
            var boundary = value.getBytes(StandardCharsets.US_ASCII);
            var opening = new byte[boundary.length + 2];
            opening[0] = '-';
            opening[1] = '-';
            System.arraycopy(boundary, 0, opening, 2, boundary.length);
            var following = new byte[boundary.length + 4];
            following[0] = '\r';
            following[1] = '\n';
            following[2] = '-';
            following[3] = '-';
            System.arraycopy(boundary, 0, following, 4, boundary.length);
            return new Boundary(opening, following);
        }

        private static boolean isBoundaryCharacter(char character) {
            return character >= '0' && character <= '9'
                    || character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || switch (character) {
                        case '\'', '(', ')', '+', '_', ',', '-', '.', '/', ':', '=', '?' -> true;
                        default -> false;
                    };
        }
    }

    /**
     * A cancellable multipart parse operation.
     *
     * <p>Use {@link #cancel(String)} when the owner knows the publisher should stop immediately;
     * the returned completion stage then fails with {@link MultipartCancelledException}.</p>
     */
    public static final class ParseOperation {
        private final CancellationToken cancellationToken;
        private final CompletableFuture<MultipartForm> completion = new CompletableFuture<>();
        private final AtomicReference<String> localCancellation = new AtomicReference<>();
        private final StreamingSubscriber subscriber;
        private volatile CancellationToken.Registration cancellationRegistration;

        private ParseOperation(MultipartParser parser, Boundary boundary, CancellationToken cancellationToken) {
            this.cancellationToken = cancellationToken;
            subscriber = new StreamingSubscriber(parser, boundary, this);
            if (cancellationToken != null) {
                cancellationRegistration = cancellationToken.onCancellation(subscriber::cancelFromOwner);
            }
        }

        private ParseOperation(Throwable failure) {
            cancellationToken = null;
            subscriber = null;
            completion.completeExceptionally(failure);
        }

        private static ParseOperation failed(Throwable failure) {
            return new ParseOperation(failure);
        }

        /** Returns the result stage for this one multipart body. */
        public CompletionStage<MultipartForm> completion() {
            return completion;
        }

        /** Returns whether parsing has reached success, failure, or cancellation. */
        public boolean isDone() {
            return completion.isDone();
        }

        /**
         * Cancels parsing and the upstream subscription with a non-blank diagnostic reason.
         *
         * @return {@code true} if this invocation changed an unfinished operation to cancelled
         */
        public boolean cancel(String reason) {
            validateCancellationReason(reason);
            return subscriber != null && subscriber.cancelFromOwner(reason);
        }

        private Flow.Subscriber<ByteBuffer> subscriber() {
            return subscriber;
        }

        private void failFromPublisher(Throwable failure) {
            if (subscriber == null) {
                completeFailure(failure);
            } else {
                subscriber.failFromPublisher(failure);
            }
        }

        private void recordLocalCancellation(String reason) {
            localCancellation.compareAndSet(null, reason);
        }

        private void checkCancellation() {
            var local = localCancellation.get();
            if (local != null) {
                throw new MultipartCancelledException(local);
            }
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                throw new MultipartCancelledException(cancellationToken.reason().orElse("request cancelled"));
            }
        }

        private void completeSuccess(MultipartForm form) {
            if (!completion.complete(form)) {
                try {
                    form.close();
                } catch (IOException ignored) {
                    // A terminal result already exists; still make the cleanup attempt.
                }
            }
            releaseCancellationRegistration();
        }

        private void completeFailure(Throwable failure) {
            var terminalFailure = Objects.requireNonNull(failure, "failure");
            // CompletableFuture normalizes a directly supplied CancellationException into a
            // generic cancellation when callers use join(). Keep the public multipart diagnostic
            // as the CompletionException cause while still preserving its cancellation type.
            if (terminalFailure instanceof CancellationException) {
                terminalFailure = new CompletionException(terminalFailure);
            }
            completion.completeExceptionally(terminalFailure);
            releaseCancellationRegistration();
        }

        private void releaseCancellationRegistration() {
            var registration = cancellationRegistration;
            cancellationRegistration = null;
            if (registration != null) {
                registration.close();
            }
        }

        private static void validateCancellationReason(String reason) {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("Cancellation reason must not be blank");
            }
        }
    }

    /**
     * A one-buffer-at-a-time Flow subscriber that keeps only headers and a boundary lookbehind in
     * memory. It is deliberately internal: no Netty or runtime types enter the public contract.
     */
    private static final class StreamingSubscriber implements Flow.Subscriber<ByteBuffer> {
        private final MultipartParser parser;
        private final Boundary boundary;
        private final ParseOperation owner;
        private final TemporaryFileManager temporaryFiles;
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicBoolean done = new AtomicBoolean();
        private final ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        private final List<MultipartPart> parts = new ArrayList<>();
        private final byte[] boundaryCandidate;

        private State state = State.INITIAL_BOUNDARY;
        private int initialBoundaryOffset;
        private int headerTerminatorPrefix;
        private int boundaryCandidateLength;
        private PartSink currentPart;
        private long receivedBytes;

        private StreamingSubscriber(MultipartParser parser, Boundary boundary, ParseOperation owner) {
            this.parser = parser;
            this.boundary = boundary;
            this.owner = owner;
            temporaryFiles = new TemporaryFileManager(
                    parser.temporaryDirectory, parser.limits.maximumTemporaryDiskBytes());
            boundaryCandidate = new byte[boundary.following().length + 2];
        }

        @Override
        public void onSubscribe(Flow.Subscription incoming) {
            Objects.requireNonNull(incoming, "incoming");
            if (!subscription.compareAndSet(null, incoming)) {
                incoming.cancel();
                return;
            }
            if (done.get()) {
                incoming.cancel();
                return;
            }
            try {
                owner.checkCancellation();
                requestNext();
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        @Override
        public void onNext(ByteBuffer item) {
            try {
                Objects.requireNonNull(item, "item");
                if (done.get()) {
                    cancelSubscription();
                    return;
                }
                owner.checkCancellation();
                var source = item.duplicate();
                var length = source.remaining();
                if ((long) length > parser.limits.maximumTotalBytes() - receivedBytes) {
                    throw new MultipartLimitExceededException(MultipartLimitExceededException.Limit.TOTAL_BYTES,
                            receivedBytes + length, parser.limits.maximumTotalBytes());
                }
                receivedBytes += length;
                while (source.hasRemaining()) {
                    if (done.get()) {
                        return;
                    }
                    accept(source.get());
                }
                if (!done.get()) {
                    requestNext();
                }
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        @Override
        public void onError(Throwable failure) {
            fail(Objects.requireNonNull(failure, "failure"));
        }

        @Override
        public void onComplete() {
            try {
                if (done.get()) {
                    return;
                }
                owner.checkCancellation();
                if (state != State.EPILOGUE && state != State.EPILOGUE_DONE) {
                    throw new MultipartParseException("Multipart body is missing its closing boundary");
                }
                if (!done.compareAndSet(false, true)) {
                    return;
                }
                owner.completeSuccess(new MultipartForm(parts, temporaryFiles));
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private boolean cancelFromOwner(String reason) {
            if (!done.compareAndSet(false, true)) {
                return false;
            }
            owner.recordLocalCancellation(reason);
            var failure = new MultipartCancelledException(reason);
            cancelSubscription();
            closeAfterFailure(failure);
            owner.completeFailure(failure);
            return true;
        }

        private void failFromPublisher(Throwable failure) {
            fail(failure);
        }

        private void fail(Throwable failure) {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            cancelSubscription();
            closeAfterFailure(failure);
            owner.completeFailure(failure);
        }

        private void requestNext() {
            if (done.get()) {
                return;
            }
            owner.checkCancellation();
            var current = subscription.get();
            if (current == null) {
                throw new IllegalStateException("Multipart publisher emitted before onSubscribe");
            }
            current.request(1);
        }

        private void cancelSubscription() {
            var current = subscription.get();
            if (current != null) {
                current.cancel();
            }
        }

        private void closeAfterFailure(Throwable failure) {
            try {
                temporaryFiles.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }

        private void accept(byte value) throws IOException {
            switch (state) {
                case INITIAL_BOUNDARY -> acceptInitialBoundary(value);
                case INITIAL_SUFFIX -> acceptInitialSuffix(value);
                case INITIAL_CLOSING_SUFFIX -> acceptInitialClosingSuffix(value);
                case INITIAL_LINE_END -> acceptInitialLineEnd(value);
                case HEADERS -> acceptHeaderByte(value);
                case BODY -> acceptBodyByte(value);
                case DELIMITER_SUFFIX -> acceptDelimiterSuffix(value);
                case DELIMITER_CLOSING_SUFFIX -> acceptDelimiterClosingSuffix(value);
                case DELIMITER_LINE_END -> acceptDelimiterLineEnd(value);
                case EPILOGUE, EPILOGUE_CR, EPILOGUE_DONE -> acceptEpilogueByte(value);
            }
        }

        private void acceptInitialBoundary(byte value) {
            var opening = boundary.opening();
            if (value != opening[initialBoundaryOffset]) {
                throw new MultipartParseException("Multipart body does not begin with its declared boundary");
            }
            initialBoundaryOffset++;
            if (initialBoundaryOffset == opening.length) {
                state = State.INITIAL_SUFFIX;
            }
        }

        private void acceptInitialSuffix(byte value) {
            if (value == '-') {
                state = State.INITIAL_CLOSING_SUFFIX;
            } else if (value == '\r') {
                state = State.INITIAL_LINE_END;
            } else {
                throw new MultipartParseException(
                        "Initial multipart boundary is not followed by CRLF or closing marker");
            }
        }

        private void acceptInitialClosingSuffix(byte value) {
            if (value != '-') {
                throw new MultipartParseException("Initial multipart closing boundary is malformed");
            }
            state = State.EPILOGUE;
        }

        private void acceptInitialLineEnd(byte value) {
            if (value != '\n') {
                throw new MultipartParseException("Initial multipart boundary is not followed by CRLF");
            }
            startHeaders();
        }

        private void startHeaders() {
            headerBytes.reset();
            headerTerminatorPrefix = 0;
            state = State.HEADERS;
        }

        private void acceptHeaderByte(byte value) throws IOException {
            headerBytes.write(value);
            var size = headerBytes.size();
            headerTerminatorPrefix = advanceHeaderTerminatorPrefix(headerTerminatorPrefix, value);
            if (headerTerminatorPrefix == HEADER_TERMINATOR.length) {
                var headerLength = size - HEADER_TERMINATOR.length;
                if (headerLength > parser.limits.maximumHeaderBytes()) {
                    throw new MultipartLimitExceededException(MultipartLimitExceededException.Limit.HEADER_BYTES,
                            headerLength, parser.limits.maximumHeaderBytes());
                }
                var copiedHeaders = headerBytes.toByteArray();
                headerBytes.reset();
                startPart(parseHeaders(copiedHeaders, 0, headerLength));
                return;
            }
            // At most three bytes can still become the CRLF CRLF terminator. This bound prevents a
            // malformed header block from retaining more than its configured budget plus that
            // fixed lookbehind.
            var definiteHeaderBytes = (long) size - headerTerminatorPrefix;
            if (definiteHeaderBytes > parser.limits.maximumHeaderBytes()) {
                throw new MultipartLimitExceededException(MultipartLimitExceededException.Limit.HEADER_BYTES,
                        definiteHeaderBytes, parser.limits.maximumHeaderBytes());
            }
        }

        private static int advanceHeaderTerminatorPrefix(int current, byte value) {
            while (current > 0 && value != HEADER_TERMINATOR[current]) {
                current = HEADER_TERMINATOR_FAILURE[current - 1];
            }
            if (value == HEADER_TERMINATOR[current]) {
                current++;
            }
            return current;
        }

        private void startPart(Headers headers) throws IOException {
            owner.checkCancellation();
            var nextPartCount = (long) parts.size() + 1;
            if (nextPartCount > parser.limits.maximumParts()) {
                throw new MultipartLimitExceededException(MultipartLimitExceededException.Limit.PARTS,
                        nextPartCount, parser.limits.maximumParts());
            }
            var disposition = parseDisposition(headers);
            var mediaType = parsePartMediaType(headers).orElse(null);
            currentPart = disposition.filename().isPresent()
                    ? new FilePartSink(
                            disposition.name(), headers, mediaType, parser.limits.maximumPartBytes(),
                            temporaryFiles, disposition.filename().orElseThrow())
                    : new FieldPartSink(
                            disposition.name(), headers, mediaType, parser.limits.maximumPartBytes());
            boundaryCandidateLength = 0;
            state = State.BODY;
        }

        private void acceptBodyByte(byte value) throws IOException {
            appendBoundaryCandidate(value);
            recoverBoundaryCandidate();
        }

        private void acceptDelimiterSuffix(byte value) throws IOException {
            appendBoundaryCandidate(value);
            if (value == '-') {
                state = State.DELIMITER_CLOSING_SUFFIX;
            } else if (value == '\r') {
                state = State.DELIMITER_LINE_END;
            } else {
                recoverBoundaryCandidate();
            }
        }

        private void acceptDelimiterClosingSuffix(byte value) throws IOException {
            appendBoundaryCandidate(value);
            if (value != '-') {
                recoverBoundaryCandidate();
                return;
            }
            boundaryCandidateLength = 0;
            finishCurrentPart();
            state = State.EPILOGUE;
        }

        private void acceptDelimiterLineEnd(byte value) throws IOException {
            appendBoundaryCandidate(value);
            if (value != '\n') {
                recoverBoundaryCandidate();
                return;
            }
            boundaryCandidateLength = 0;
            finishCurrentPart();
            startHeaders();
        }

        private void appendBoundaryCandidate(byte value) {
            if (boundaryCandidateLength == boundaryCandidate.length) {
                throw new IllegalStateException("Multipart boundary lookbehind overflow");
            }
            boundaryCandidate[boundaryCandidateLength++] = value;
        }

        private void recoverBoundaryCandidate() throws IOException {
            var retained = longestPrefixSuffix(
                    boundaryCandidate, boundaryCandidateLength, boundary.following());
            var writeLength = boundaryCandidateLength - retained;
            if (writeLength > 0) {
                if (currentPart == null) {
                    throw new MultipartParseException("Multipart body bytes appeared before a part header block");
                }
                currentPart.write(boundaryCandidate, 0, writeLength);
                if (retained > 0) {
                    System.arraycopy(boundaryCandidate, writeLength, boundaryCandidate, 0, retained);
                }
            }
            boundaryCandidateLength = retained;
            state = retained == boundary.following().length ? State.DELIMITER_SUFFIX : State.BODY;
        }

        private static int longestPrefixSuffix(byte[] candidate, int candidateLength, byte[] prefix) {
            var upperBound = Math.min(candidateLength, prefix.length);
            for (var length = upperBound; length > 0; length--) {
                var offset = candidateLength - length;
                var matches = true;
                for (var index = 0; index < length; index++) {
                    if (candidate[offset + index] != prefix[index]) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    return length;
                }
            }
            return 0;
        }

        private void finishCurrentPart() throws IOException {
            owner.checkCancellation();
            if (currentPart == null) {
                throw new MultipartParseException("Multipart boundary has no active part");
            }
            var finished = currentPart.finish();
            currentPart = null;
            parts.add(finished);
            owner.checkCancellation();
        }

        private void acceptEpilogueByte(byte value) {
            switch (state) {
                case EPILOGUE -> {
                    if (value != '\r') {
                        throw new MultipartParseException("Multipart body contains unsupported epilogue bytes");
                    }
                    state = State.EPILOGUE_CR;
                }
                case EPILOGUE_CR -> {
                    if (value != '\n') {
                        throw new MultipartParseException("Multipart body contains unsupported epilogue bytes");
                    }
                    state = State.EPILOGUE_DONE;
                }
                case EPILOGUE_DONE -> throw new MultipartParseException(
                        "Multipart body contains unsupported epilogue bytes");
                default -> throw new IllegalStateException("Unexpected multipart parser state: " + state);
            }
        }
    }

    private enum State {
        INITIAL_BOUNDARY,
        INITIAL_SUFFIX,
        INITIAL_CLOSING_SUFFIX,
        INITIAL_LINE_END,
        HEADERS,
        BODY,
        DELIMITER_SUFFIX,
        DELIMITER_CLOSING_SUFFIX,
        DELIMITER_LINE_END,
        EPILOGUE,
        EPILOGUE_CR,
        EPILOGUE_DONE
    }

    private abstract static class PartSink {
        private final String name;
        private final Headers headers;
        private final MediaType mediaType;
        private final long maximumBytes;
        private long size;

        private PartSink(String name, Headers headers, MediaType mediaType, long maximumBytes) {
            this.name = name;
            this.headers = headers;
            this.mediaType = mediaType;
            this.maximumBytes = maximumBytes;
        }

        private final void write(byte[] source, int offset, int length) throws IOException {
            if (length > maximumBytes - size) {
                throw new MultipartLimitExceededException(MultipartLimitExceededException.Limit.PART_BYTES,
                        size + length, maximumBytes);
            }
            accept(source, offset, length);
            size += length;
        }

        final String name() {
            return name;
        }

        final Headers headers() {
            return headers;
        }

        final MediaType mediaType() {
            return mediaType;
        }

        abstract void accept(byte[] source, int offset, int length) throws IOException;

        abstract MultipartPart finish() throws IOException;
    }

    private static final class FieldPartSink extends PartSink {
        private final ByteArrayOutputStream content = new ByteArrayOutputStream();

        private FieldPartSink(String name, Headers headers, MediaType mediaType, long maximumBytes) {
            super(name, headers, mediaType, maximumBytes);
        }

        @Override
        void accept(byte[] source, int offset, int length) {
            content.write(source, offset, length);
        }

        @Override
        MultipartPart finish() {
            return MultipartPart.field(name(), headers(), mediaType(), content.toByteArray());
        }
    }

    private static final class FilePartSink extends PartSink {
        private final TemporaryFileManager temporaryFiles;
        private final TemporaryFileManager.PendingUpload pendingUpload;
        private final byte[] writeBuffer;
        private int buffered;

        private FilePartSink(
                String name,
                Headers headers,
                MediaType mediaType,
                long maximumBytes,
                TemporaryFileManager temporaryFiles,
                String filename
        ) throws IOException {
            super(name, headers, mediaType, maximumBytes);
            this.temporaryFiles = temporaryFiles;
            pendingUpload = temporaryFiles.beginUpload(filename, mediaType);
            writeBuffer = new byte[(int) Math.min(FILE_WRITE_BUFFER_BYTES, maximumBytes)];
        }

        @Override
        void accept(byte[] source, int offset, int length) throws IOException {
            var remaining = length;
            var sourceOffset = offset;
            while (remaining > 0) {
                var copied = Math.min(remaining, writeBuffer.length - buffered);
                System.arraycopy(source, sourceOffset, writeBuffer, buffered, copied);
                buffered += copied;
                sourceOffset += copied;
                remaining -= copied;
                if (buffered == writeBuffer.length) {
                    flush();
                }
            }
        }

        @Override
        MultipartPart finish() throws IOException {
            flush();
            return MultipartPart.upload(name(), headers(), mediaType(), temporaryFiles.finish(pendingUpload));
        }

        private void flush() throws IOException {
            if (buffered == 0) {
                return;
            }
            temporaryFiles.write(pendingUpload, writeBuffer, 0, buffered);
            buffered = 0;
        }
    }

    /** Immutable count and byte budgets used by one parser. */
    public record Limits(
            long maximumPartBytes,
            int maximumParts,
            long maximumTotalBytes,
            long maximumTemporaryDiskBytes,
            long maximumHeaderBytes
    ) {
        /** Default maximum bytes in one individual part. */
        public static final long DEFAULT_MAXIMUM_PART_BYTES = 8L * 1024 * 1024;
        /** Default maximum number of parts. */
        public static final int DEFAULT_MAXIMUM_PARTS = 64;
        /** Default maximum raw bytes accepted from the multipart body publisher. */
        public static final long DEFAULT_MAXIMUM_TOTAL_BYTES = 16L * 1024 * 1024;
        /** Default total file payload bytes allowed in parser-owned temporary storage. */
        public static final long DEFAULT_MAXIMUM_TEMPORARY_DISK_BYTES = 16L * 1024 * 1024;
        /** Default bytes in one part's header block, excluding its terminating blank line. */
        public static final long DEFAULT_MAXIMUM_HEADER_BYTES = 16L * 1024;

        private static final Limits DEFAULTS = new Limits(
                DEFAULT_MAXIMUM_PART_BYTES,
                DEFAULT_MAXIMUM_PARTS,
                DEFAULT_MAXIMUM_TOTAL_BYTES,
                DEFAULT_MAXIMUM_TEMPORARY_DISK_BYTES,
                DEFAULT_MAXIMUM_HEADER_BYTES);

        /** Validates one complete limits snapshot. */
        public Limits {
            maximumPartBytes = requirePositive(maximumPartBytes, "maximumPartBytes");
            if (maximumParts <= 0) {
                throw new IllegalArgumentException("maximumParts must be greater than zero: " + maximumParts);
            }
            maximumTotalBytes = requirePositive(maximumTotalBytes, "maximumTotalBytes");
            if (maximumTotalBytes > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("maximumTotalBytes must not exceed " + Integer.MAX_VALUE);
            }
            if (maximumTemporaryDiskBytes < 0) {
                throw new IllegalArgumentException("maximumTemporaryDiskBytes must not be negative");
            }
            maximumHeaderBytes = requirePositive(maximumHeaderBytes, "maximumHeaderBytes");
        }

        /** Returns the documented default limits. */
        public static Limits defaults() {
            return DEFAULTS;
        }

        /** Returns a builder initialized with the documented defaults. */
        public static Builder builder() {
            return new Builder();
        }

        /** Returns a builder initialized from this snapshot. */
        public Builder toBuilder() {
            return new Builder(this);
        }

        private static long requirePositive(long value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be greater than zero: " + value);
            }
            return value;
        }

        /** Mutable builder for a {@link Limits} snapshot. */
        public static final class Builder {
            private long maximumPartBytes = DEFAULT_MAXIMUM_PART_BYTES;
            private int maximumParts = DEFAULT_MAXIMUM_PARTS;
            private long maximumTotalBytes = DEFAULT_MAXIMUM_TOTAL_BYTES;
            private long maximumTemporaryDiskBytes = DEFAULT_MAXIMUM_TEMPORARY_DISK_BYTES;
            private long maximumHeaderBytes = DEFAULT_MAXIMUM_HEADER_BYTES;

            private Builder() {
            }

            private Builder(Limits limits) {
                maximumPartBytes = limits.maximumPartBytes;
                maximumParts = limits.maximumParts;
                maximumTotalBytes = limits.maximumTotalBytes;
                maximumTemporaryDiskBytes = limits.maximumTemporaryDiskBytes;
                maximumHeaderBytes = limits.maximumHeaderBytes;
            }

            /** Sets the maximum payload bytes in any one parsed part. */
            public Builder maximumPartBytes(long maximumPartBytes) {
                this.maximumPartBytes = requirePositive(maximumPartBytes, "maximumPartBytes");
                return this;
            }

            /** Sets the maximum part occurrence count. */
            public Builder maximumParts(int maximumParts) {
                if (maximumParts <= 0) {
                    throw new IllegalArgumentException("maximumParts must be greater than zero: " + maximumParts);
                }
                this.maximumParts = maximumParts;
                return this;
            }

            /** Sets the maximum raw bytes accepted from the multipart publisher. */
            public Builder maximumTotalBytes(long maximumTotalBytes) {
                if (maximumTotalBytes > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("maximumTotalBytes must not exceed " + Integer.MAX_VALUE);
                }
                this.maximumTotalBytes = requirePositive(maximumTotalBytes, "maximumTotalBytes");
                return this;
            }

            /** Sets the total file payload bytes allowed in parser-owned temporary storage. */
            public Builder maximumTemporaryDiskBytes(long maximumTemporaryDiskBytes) {
                if (maximumTemporaryDiskBytes < 0) {
                    throw new IllegalArgumentException("maximumTemporaryDiskBytes must not be negative");
                }
                this.maximumTemporaryDiskBytes = maximumTemporaryDiskBytes;
                return this;
            }

            /** Sets the maximum header-block bytes for an individual part. */
            public Builder maximumHeaderBytes(long maximumHeaderBytes) {
                this.maximumHeaderBytes = requirePositive(maximumHeaderBytes, "maximumHeaderBytes");
                return this;
            }

            /** Builds the immutable limits snapshot. */
            public Limits build() {
                return new Limits(maximumPartBytes, maximumParts, maximumTotalBytes,
                        maximumTemporaryDiskBytes, maximumHeaderBytes);
            }
        }
    }

    /** Builder for one {@link MultipartParser}. */
    public static final class Builder {
        private Limits limits = DEFAULT_LIMITS;
        private Path temporaryDirectory = DEFAULT_TEMPORARY_DIRECTORY;

        private Builder() {
        }

        /** Uses an immutable multipart limits snapshot. */
        public Builder limits(Limits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets a trusted base directory under which a unique per-form temporary directory is made.
         * The base itself is never removed by the parser.
         */
        public Builder temporaryDirectory(Path temporaryDirectory) {
            this.temporaryDirectory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
            return this;
        }

        /** Builds an immutable parser. */
        public MultipartParser build() {
            return new MultipartParser(limits, temporaryDirectory);
        }
    }
}
