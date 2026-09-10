package io.wavejava.wave.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.render.Rendered;
import io.wavejava.wave.api.stream.BodyPublisher;
import io.wavejava.wave.internal.http.ResponseData;
import io.wavejava.wave.internal.http.ResponseDataReader;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestResponseTest {
    @Test
    void responsePublicSurfaceHidesTransportPayloadState() {
        assertTrue(Response.class.isSealed());
        assertTrue(java.util.Arrays.stream(Response.class.getMethods())
                .noneMatch(method -> method.getName().equals("body")));
        assertEquals("io.wavejava.wave.internal.http.InternalResponse",
                Response.class.getPermittedSubclasses()[0].getName());
    }

    @Test
    void requestSnapshotsMetadataAndSupportsImmutableRouteBinding() {
        var request = Request.builder()
                .method(HttpMethod.POST)
                .target("/users/42?tag=java&tag=netty")
                .header("Cookie", "session=abc; theme=dark")
                .body(Body.utf8("payload", 16))
                .build();

        var bound = request.withPathParameters(Map.of("id", "42"));

        assertEquals(HttpMethod.POST, bound.method());
        assertEquals("/users/42", bound.path());
        assertEquals(2, bound.queryParameters("tag").size());
        assertEquals("42", bound.pathParameter("id").orElseThrow());
        assertEquals(2, bound.cookies().size());
        assertThrows(UnsupportedOperationException.class, () -> bound.pathParameters().put("id", "43"));
        assertEquals("payload", bound.body().text());
        assertTrue(request.body().isConsumed());
    }

    @Test
    void responseCommitsWriterOutputAndCannotBeChangedAfterward() {
        var response = Response.create().status(201).header("Location", "/users/42").text("created");

        assertEquals(ResponseState.COMMITTED, response.state());
        assertEquals(201, response.status());
        assertEquals("text/plain; charset=UTF-8", response.headers().first("Content-Type").orElseThrow());
        assertEquals("7", response.headers().first("Content-Length").orElseThrow());
        assertEquals("created", new String(ResponseDataReader.read(response).byteContent().orElseThrow()));
        assertThrows(IllegalStateException.class, () -> response.header("X-Late", "no"));
    }

    @Test
    void responseCommitsBoundedRenderedOutputWithSupplementalHeaders() {
        var response = Response.create().header("Cache-Control", "private")
                .render(Rendered.of("wave".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        MediaType.TEXT_PLAIN_UTF_8, Headers.of("Vary", "Accept")));

        assertEquals(ResponseState.COMMITTED, response.state());
        assertEquals("text/plain; charset=UTF-8", response.headers().first("Content-Type").orElseThrow());
        assertEquals("4", response.headers().first("Content-Length").orElseThrow());
        assertEquals("private", response.headers().first("Cache-Control").orElseThrow());
        assertEquals("Accept", response.headers().first("Vary").orElseThrow());
        assertEquals("wave", new String(ResponseDataReader.read(response).byteContent().orElseThrow()));
    }

    @Test
    void requestCarriesOptionalExecutionContextWithoutChangingTheOriginalRequest() {
        var token = CancellationToken.create();
        var context = RequestContext.builder("request-42")
                .deadline(Deadline.at(Instant.parse("2030-01-01T00:00:00Z")))
                .cancellationToken(token)
                .build();
        var request = Request.of(HttpMethod.GET, "/users/42");
        var contextual = request.withContext(context);
        var routed = contextual.withPathParameters(Map.of("id", "42"));
        var builtWithContext = Request.builder().path("/jobs").context(context).build();

        assertTrue(request.context().isEmpty());
        assertTrue(request.deadline().isEmpty());
        assertTrue(request.cancellationToken().isEmpty());
        assertSame(context, contextual.context().orElseThrow());
        assertEquals(context.deadline().orElseThrow(), contextual.deadline().orElseThrow());
        assertSame(token, contextual.cancellationToken().orElseThrow());
        assertSame(context, routed.context().orElseThrow());
        assertSame(context, builtWithContext.context().orElseThrow());
    }

    @Test
    void problemAdoptsProblemStatusAndBodylessStatusCommits() {
        var problemResponse = Response.create().problem(Problem.of(422, "validation_failed"));
        var noContent = Response.create().status(204);

        assertEquals(422, problemResponse.status());
        assertEquals(ResponseData.Kind.PROBLEM, ResponseDataReader.read(problemResponse).kind());
        assertEquals(ResponseState.COMMITTED, noContent.state());
        assertEquals(null, ResponseDataReader.read(noContent));
    }

    @Test
    void requestAndResponseExposeMutuallyExclusiveFlowBodyContracts() {
        var publisher = BodyPublisher.empty();
        var streamingRequest = Request.builder().bodyPublisher(publisher).build();

        assertTrue(streamingRequest.hasStreamingBody());
        assertTrue(streamingRequest.bodyPublisher().isPresent());
        assertThrows(IllegalStateException.class, streamingRequest::body);
        assertThrows(IllegalStateException.class, () -> Request.builder()
                .body(Body.empty())
                .bodyPublisher(publisher));

        var response = Response.create().stream(BodyPublisher.empty());

        assertEquals(ResponseState.COMMITTED, response.state());
        assertEquals(ResponseData.Kind.STREAM, ResponseDataReader.read(response).kind());
        assertEquals("application/octet-stream", response.headers().first("Content-Type").orElseThrow());
        assertTrue(ResponseDataReader.read(response).stream().isPresent());
        assertThrows(IllegalStateException.class, () -> Response.create()
                .header("Content-Length", "1")
                .stream(BodyPublisher.empty()));
        assertThrows(IllegalStateException.class, () -> Response.create().status(204).stream(BodyPublisher.empty()));
    }
}
