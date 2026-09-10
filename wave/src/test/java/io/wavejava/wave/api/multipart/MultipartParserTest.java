package io.wavejava.wave.api.multipart;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.wavejava.wave.api.http.CancellationToken;
import io.wavejava.wave.api.http.MediaType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultipartParserTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesChunkedFormAndCleansParserOwnedUploads() throws Exception {
        var boundary = "wave-boundary";
        var callerOwnedFile = Files.writeString(temporaryDirectory.resolve("caller-owned.txt"), "keep");
        var publisher = new ControlledPublisher();
        var parser = parser(defaultLimits());
        var operation = parser.begin(publisher, multipartType(boundary), null);

        assertEquals(1, publisher.requested());
        publishInChunks(publisher, validBody(boundary), 5, 11, 23, 37);
        publisher.complete();

        var form = operation.completion().toCompletableFuture().join();
        assertEquals(2, form.parts().size());
        assertEquals("wave", form.first("title").orElseThrow().text());

        var uploadPart = form.first("file").orElseThrow();
        assertTrue(uploadPart.isUpload());
        var upload = uploadPart.upload().orElseThrow();
        assertEquals("report.txt", upload.filename());
        assertEquals(MediaType.TEXT_PLAIN, upload.mediaType().orElseThrow());
        assertEquals(5, upload.size());
        var path = upload.path();
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path));
        assertTrue(publisher.requested() >= 2, "the collector must demand buffers incrementally");

        form.close();

        assertFalse(Files.exists(path));
        assertFalse(upload.isOpen());
        assertThrows(IllegalStateException.class, upload::path);
        assertTrue(Files.isDirectory(temporaryDirectory));
        assertTrue(Files.exists(callerOwnedFile), "the caller-owned base directory must not be deleted");
        Files.delete(callerOwnedFile);
        assertDirectoryEmpty(temporaryDirectory);
    }

    @Test
    void streamsFilePayloadToTemporaryStorageBeforeTheClosingBoundaryArrives() throws Exception {
        var boundary = "wave-boundary";
        var payload = new byte[32 * 1024];
        for (var index = 0; index < payload.length; index++) {
            payload[index] = 'x';
        }
        var publisher = new ControlledPublisher();
        var parser = parser(limits()
                .maximumPartBytes(64 * 1024)
                .maximumTotalBytes(128 * 1024)
                .maximumTemporaryDiskBytes(64 * 1024)
                .build());
        var operation = parser.begin(publisher, multipartType(boundary), null);

        publisher.emit(ByteBuffer.wrap(filePreamble(boundary)));
        publisher.emit(ByteBuffer.wrap(payload, 0, 16 * 1024).slice());

        var materialized = onlyTemporaryFile(temporaryDirectory);
        assertEquals(16 * 1024, Files.size(materialized),
                "file payload must be flushed before its closing boundary is available");
        assertFalse(operation.isDone());

        publisher.emit(ByteBuffer.wrap(payload, 16 * 1024, payload.length - 16 * 1024).slice());
        var closing = closingBoundary(boundary);
        publisher.emit(ByteBuffer.wrap(closing, 0, 3).slice());
        publisher.emit(ByteBuffer.wrap(closing, 3, closing.length - 3).slice());
        publisher.complete();

        var form = operation.completion().toCompletableFuture().join();
        var upload = form.first("file").orElseThrow().upload().orElseThrow();
        assertArrayEquals(payload, Files.readAllBytes(upload.path()));
        form.close();
        assertDirectoryEmpty(temporaryDirectory);
    }

    @Test
    void rejectsMalformedFramingAndCleansAnyPriorUpload() {
        var boundary = "wave-boundary";
        var publisher = new ControlledPublisher();
        var operation = parser(defaultLimits()).begin(publisher, multipartType(boundary), null);

        publishInChunks(publisher, malformedAfterFileBody(boundary), 17, 49);
        publisher.complete();

        assertInstanceOf(MultipartParseException.class, failure(operation));
        assertDirectoryEmpty(temporaryDirectory);
    }

    @Test
    void cancellationCleansAnIncrementallyWrittenTemporaryUpload() throws Exception {
        var boundary = "wave-boundary";
        var publisher = new ControlledPublisher();
        var parser = parser(limits()
                .maximumPartBytes(64 * 1024)
                .maximumTotalBytes(128 * 1024)
                .maximumTemporaryDiskBytes(64 * 1024)
                .build());
        var operation = parser.begin(publisher, multipartType(boundary), null);
        var payload = new byte[16 * 1024];

        publisher.emit(ByteBuffer.wrap(filePreamble(boundary)));
        publisher.emit(ByteBuffer.wrap(payload));
        var materialized = onlyTemporaryFile(temporaryDirectory);
        assertEquals(payload.length, Files.size(materialized));

        assertTrue(operation.cancel("client disconnected during upload"));
        assertTrue(publisher.cancelled());
        assertInstanceOf(MultipartCancelledException.class, failure(operation));
        assertDirectoryEmpty(temporaryDirectory);
    }

    @Test
    void propagatesPublisherApplicationFailureWithoutCreatingTemporaryFiles() {
        var publisher = new ControlledPublisher();
        var operation = parser(defaultLimits()).begin(publisher, multipartType("wave-boundary"), null);

        publisher.fail(new IllegalStateException("upstream failed"));

        var failure = failure(operation);
        assertInstanceOf(IllegalStateException.class, failure);
        assertEquals("upstream failed", failure.getMessage());
        assertDirectoryEmpty(temporaryDirectory);
    }

    @Test
    void cancellationCancelsTheSubscriptionAndFailsTheOperation() {
        var publisher = new ControlledPublisher();
        var operation = parser(defaultLimits()).begin(publisher, multipartType("wave-boundary"), null);

        assertTrue(operation.cancel("client disconnected"));
        assertTrue(publisher.cancelled());
        var failure = assertInstanceOf(MultipartCancelledException.class, failure(operation));
        assertEquals("client disconnected", failure.reason());
        assertFalse(operation.cancel("second cancellation"));
        assertDirectoryEmpty(temporaryDirectory);
    }

    @Test
    void requestTokenCancellationIsObservedBeforeAnotherBufferIsAccepted() {
        var token = CancellationToken.create();
        var publisher = new ControlledPublisher();
        var operation = parser(defaultLimits()).begin(publisher, multipartType("wave-boundary"), token);

        token.cancel("deadline exceeded");
        publisher.emit(ByteBuffer.wrap("ignored".getBytes(StandardCharsets.US_ASCII)));

        var failure = assertInstanceOf(MultipartCancelledException.class, failure(operation));
        assertEquals("deadline exceeded", failure.reason());
        assertTrue(publisher.cancelled());
    }

    @Test
    void requestTokenCancellationImmediatelyCleansAStoppedIncrementalUpload() throws Exception {
        var boundary = "wave-boundary";
        var token = CancellationToken.create();
        var publisher = new ControlledPublisher();
        var operation = parser(limits()
                .maximumPartBytes(64 * 1024)
                .maximumTotalBytes(128 * 1024)
                .maximumTemporaryDiskBytes(64 * 1024)
                .build()).begin(publisher, multipartType(boundary), token);

        publisher.emit(ByteBuffer.wrap(filePreamble(boundary)));
        publisher.emit(ByteBuffer.wrap(new byte[16 * 1024]));
        assertEquals(16 * 1024, Files.size(onlyTemporaryFile(temporaryDirectory)));

        assertTrue(token.cancel("deadline exceeded while peer stopped sending"));

        var failure = assertInstanceOf(MultipartCancelledException.class, failure(operation));
        assertEquals("deadline exceeded while peer stopped sending", failure.reason());
        assertTrue(publisher.cancelled(), "token cancellation must cancel the silent upstream subscription");
        assertDirectoryEmpty(temporaryDirectory);
    }

    @Test
    void enforcesTotalPartCountHeaderAndTemporaryDiskBudgets() {
        var totalPublisher = new ControlledPublisher();
        var totalOperation = parser(limits().maximumTotalBytes(4).build())
                .begin(totalPublisher, multipartType("wave-boundary"), null);
        totalPublisher.emit(ByteBuffer.wrap("12345".getBytes(StandardCharsets.US_ASCII)));
        var total = assertInstanceOf(MultipartLimitExceededException.class, failure(totalOperation));
        assertEquals(MultipartLimitExceededException.Limit.TOTAL_BYTES, total.limit());
        assertTrue(totalPublisher.cancelled());

        var partPublisher = new ControlledPublisher();
        var partOperation = parser(limits().maximumPartBytes(3).build())
                .begin(partPublisher, multipartType("wave-boundary"), null);
        publishInChunks(partPublisher, fieldBody("wave-boundary", "field", "abcdef"), 9, 31);
        partPublisher.complete();
        var part = assertInstanceOf(MultipartLimitExceededException.class, failure(partOperation));
        assertEquals(MultipartLimitExceededException.Limit.PART_BYTES, part.limit());

        var countPublisher = new ControlledPublisher();
        var countOperation = parser(limits().maximumParts(1).build())
                .begin(countPublisher, multipartType("wave-boundary"), null);
        publishInChunks(countPublisher, twoFieldBody("wave-boundary"), 11, 47);
        countPublisher.complete();
        var count = assertInstanceOf(MultipartLimitExceededException.class, failure(countOperation));
        assertEquals(MultipartLimitExceededException.Limit.PARTS, count.limit());

        var headerPublisher = new ControlledPublisher();
        var headerOperation = parser(limits().maximumHeaderBytes(10).build())
                .begin(headerPublisher, multipartType("wave-boundary"), null);
        publishInChunks(headerPublisher, fieldBody("wave-boundary", "field", "value"), 7, 23);
        var header = assertInstanceOf(MultipartLimitExceededException.class, failure(headerOperation));
        assertEquals(MultipartLimitExceededException.Limit.HEADER_BYTES, header.limit());

        var diskPublisher = new ControlledPublisher();
        var diskOperation = parser(limits().maximumTemporaryDiskBytes(3).build())
                .begin(diskPublisher, multipartType("wave-boundary"), null);
        publishInChunks(diskPublisher, fileBody("wave-boundary", "abcdef"), 13, 59);
        diskPublisher.complete();
        var disk = assertInstanceOf(MultipartLimitExceededException.class, failure(diskOperation));
        assertEquals(MultipartLimitExceededException.Limit.TEMPORARY_DISK_BYTES, disk.limit());
        assertDirectoryEmpty(temporaryDirectory);
    }

    @Test
    void rejectsMissingOrMismatchedBoundaryBeforeAResultIsProduced() {
        var missingBoundary = parser(defaultLimits()).begin(
                new ControlledPublisher(), MediaType.of("multipart", "form-data"), null);
        assertInstanceOf(MultipartParseException.class, failure(missingBoundary));

        var publisher = new ControlledPublisher();
        var mismatched = parser(defaultLimits()).begin(publisher, multipartType("expected"), null);
        publishInChunks(publisher, fieldBody("actual", "field", "value"), 16);
        publisher.complete();
        assertInstanceOf(MultipartParseException.class, failure(mismatched));
    }

    private MultipartParser parser(MultipartParser.Limits limits) {
        return MultipartParser.builder().limits(limits).temporaryDirectory(temporaryDirectory).build();
    }

    private static MultipartParser.Limits defaultLimits() {
        return limits().build();
    }

    private static MultipartParser.Limits.Builder limits() {
        return MultipartParser.Limits.builder()
                .maximumPartBytes(1024)
                .maximumParts(8)
                .maximumTotalBytes(4096)
                .maximumTemporaryDiskBytes(2048)
                .maximumHeaderBytes(512);
    }

    private static MediaType multipartType(String boundary) {
        return MediaType.of("multipart", "form-data").withParameter("boundary", boundary);
    }

    private static byte[] validBody(String boundary) {
        return ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n"
                + "\r\n"
                + "wave\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"..\\\\report.txt\"\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "hello\r\n"
                + "--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] malformedAfterFileBody(String boundary) {
        return ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"report.txt\"\r\n"
                + "\r\n"
                + "hello\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"broken\"\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] fieldBody(String boundary, String name, String value) {
        return ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n"
                + "\r\n"
                + value + "\r\n"
                + "--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] twoFieldBody(String boundary) {
        return ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"one\"\r\n"
                + "\r\n"
                + "1\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"two\"\r\n"
                + "\r\n"
                + "2\r\n"
                + "--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] fileBody(String boundary, String value) {
        return ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"report.txt\"\r\n"
                + "\r\n"
                + value + "\r\n"
                + "--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] filePreamble(String boundary) {
        return ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"report.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] closingBoundary(String boundary) {
        return ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void publishInChunks(ControlledPublisher publisher, byte[] body, int... chunkEnds) {
        var start = 0;
        for (var end : chunkEnds) {
            if (end >= body.length) {
                break;
            }
            publisher.emit(ByteBuffer.wrap(body, start, end - start).slice());
            start = end;
        }
        if (start < body.length) {
            publisher.emit(ByteBuffer.wrap(body, start, body.length - start).slice());
        }
    }

    private static Throwable failure(MultipartParser.ParseOperation operation) {
        try {
            operation.completion().toCompletableFuture().join();
            fail("Expected multipart operation to fail");
            throw new AssertionError("unreachable");
        } catch (CompletionException failure) {
            return failure.getCause();
        } catch (java.util.concurrent.CancellationException failure) {
            return failure;
        }
    }

    private static void assertDirectoryEmpty(Path directory) {
        assertTrue(Files.isDirectory(directory), () -> "temporary base directory was deleted: " + directory);
        try (var paths = Files.list(directory)) {
            assertTrue(paths.findAny().isEmpty(), () -> "temporary directory is not empty: " + directory);
        } catch (IOException failure) {
            throw new AssertionError("Could not inspect temporary directory", failure);
        }
    }

    private static Path onlyTemporaryFile(Path directory) {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).findFirst()
                    .orElseThrow(() -> new AssertionError("Expected a materialized temporary upload"));
        } catch (IOException failure) {
            throw new AssertionError("Could not inspect temporary upload", failure);
        }
    }

    private static final class ControlledPublisher implements Flow.Publisher<ByteBuffer> {
        private final ControlledSubscription subscription = new ControlledSubscription();
        private Flow.Subscriber<? super ByteBuffer> subscriber;

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            if (this.subscriber != null) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long ignored) {
                    }

                    @Override
                    public void cancel() {
                    }
                });
                subscriber.onError(new IllegalStateException("Only one subscriber is supported"));
                return;
            }
            this.subscriber = subscriber;
            subscription.owner = subscriber;
            subscriber.onSubscribe(subscription);
        }

        void emit(ByteBuffer value) {
            if (subscription.cancelled) {
                return;
            }
            if (subscription.outstanding <= 0) {
                throw new AssertionError("Publisher emitted without subscriber demand");
            }
            subscription.outstanding--;
            subscriber.onNext(value);
        }

        void complete() {
            if (!subscription.cancelled) {
                subscriber.onComplete();
            }
        }

        void fail(Throwable failure) {
            if (!subscription.cancelled) {
                subscriber.onError(failure);
            }
        }

        long requested() {
            return subscription.requested;
        }

        boolean cancelled() {
            return subscription.cancelled;
        }

        private static final class ControlledSubscription implements Flow.Subscription {
            private Flow.Subscriber<? super ByteBuffer> owner;
            private long requested;
            private long outstanding;
            private boolean cancelled;

            @Override
            public void request(long requested) {
                if (cancelled) {
                    return;
                }
                if (requested <= 0) {
                    cancelled = true;
                    owner.onError(new IllegalArgumentException("Flow demand must be positive"));
                    return;
                }
                this.requested += requested;
                outstanding += requested;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        }
    }
}
