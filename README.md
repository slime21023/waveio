# WaveIO

> **A minimal, AOT-friendly HTTP/1.1 server toolkit for Java 21+**  
> Engineered for instant startup, pure modularity, and rock-solid protocol correctness without reflection or classpath scanning.

---

## 🎯 Goal & Design Philosophy

Modern Java web frameworks often carry extensive reflection, runtime class analysis, and heavy dependency graphs that complicate ahead-of-time (AOT) compilation and cloud-native deployments. **WaveIO** takes a different path:

- **AOT-Native by Design**: Zero classpath scanning, zero annotation processors, and zero required reflection. WaveIO compiles directly to GraalVM Native Image with instant startup and predictable memory footprints.
- **Netty Power, Clean JDK Surface**: Harnesses Netty 4.1's battle-tested asynchronous I/O engine internally, while exposing **zero Netty types** in its public API. The entire surface relies exclusively on standard Java 21 idioms (`record`, `Flow.Publisher`, `CompletionStage`, and Virtual Threads).
- **Dual Execution Engine**: Explicit handler differentiation prevents event-loop starvation:
  - **Event-Loop Handlers** (`get`, `post`, etc.) run directly on Netty I/O threads for ultra-fast, non-blocking computations.
  - **Virtual-Thread Handlers** (`blockingGet`, `blockingPost`, etc.) run on lightweight Java virtual threads for blocking database and filesystem I/O.
  - **Async Handlers** (`asyncGet`, `asyncPost`, etc.) return `CompletionStage<HttpResponse>` for reactive stage-driven workflows.
- **Strict Protocol Correctness & Pipelining Order**: Full RFC 7230/7231 compliance. Head-of-line response ordering is strictly preserved across asynchronous and virtual-thread handlers, with automatic HEAD routing fallback and `100 Continue` queue alignment.
- **Echo-Inspired Composable Ergonomics**: Expressive, fluent routing with nested groups, declarative middleware scoping, and centralized error handling.

---

## ✨ Features at a Glance

- 🚀 **Explicit & Composable Routing**: Path parameters (`:id`), wildcards (`*path`), route grouping (`group`), immutable router snapshots, and modular routing (`RouteModule`).
- ⚡ **Virtual Thread & Async Handlers**: First-class support for blocking handlers on virtual threads and reactive `CompletionStage` pipelines without blocking Netty event loops.
- 🌊 **JDK-Native Reactive Streaming**: Request and response streaming powered by `Flow.Publisher<ByteBuffer>` with automated TCP backpressure, demand trampolining, and stall deadlines.
- 🛡️ **Production-Grade Overload Defense**:
  - **Global Admission Control**: `maxConnections` enforces server-wide socket capacity before HTTP decoding.
  - **Bounded Pipelining**: `maxPendingRequestsPerConnection` pauses TCP auto-read and rejects flood batches.
  - **4-Tier Timeout Architecture**: Granular `handlerTimeout` (with virtual-thread interruption), `idleTimeout`, `readTimeout`, and `writeTimeout`.
- 🔒 **Security & Hardening**:
  - **Strict Request Target Parsing**: Segment-by-segment percent-decoding with UTF-8 enforcement; rejects path traversal (`..`), encoded separators (`%2F`), and control characters.
  - **Hardened Static Files**: Containment verification using `toRealPath()` against Windows prefix escapes and symlink bypasses; built-in `If-Modified-Since` (304) handling.
  - **Safe Cookies**: Validates character sets and enforces `SameSite=None` with `Secure`.
  - **Native TLS Support**: Validates PEM certificate chains and PKCS#8 private keys at startup.
- 🔌 **Pluggable Body Codec**: Decoupled `BodyCodec` seam for value body serialization (`HttpResponse.value(...)` / `request.bodyAs(Class)`) with zero built-in JSON dependencies.
- 📊 **Unified Observability SPI**: `RequestObserver` captures exact path templates, route metadata, elapsed duration, and isolated terminal outcomes (`SUCCESS`, `FAILURE`, `TIMEOUT`, `DISCONNECTED`).
- 📦 **Pure JPMS Layering**: Strictly separated public packages; internal transport internals remain unexported and encapsulated.

---

## ⚡ Quickstart

### 1. Add Dependency

```xml
<dependency>
    <groupId>io.waveio</groupId>
    <artifactId>waveio-core</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

### 2. Bootstrap Server

```java
var userHandler = new UserHandler(userRepository);

var router = Router.builder()
        .get("/health", request -> HttpResponse.text("UP"))
        .get("/users/:id", RouteMetadata.builder()
                .name("users.show")
                .attribute("resource", "user")
                .build(), userHandler::find)
        .asyncGet("/remote-health", request -> remoteCheck()
                .thenApply(result -> HttpResponse.json(result)))
        .group("/users", users -> users
                .get("", userHandler::list)
                .blockingGet("/:id", userHandler::find))
        .build();

HttpServer.builder()
        .port(8080)
        .router(router)
        .use((request, chain) -> chain.next(request))
        .observe(observation -> requestMetrics.record(observation))
        .build()
        .run();
```

---

## 📦 API Packages & JPMS Architecture

WaveIO strictly enforces acyclic dependency layering across 6 exported packages. A package tells you exactly what kind of component you are interacting with:

| Package | Purpose | Depends On |
| :--- | :--- | :--- |
| `io.waveio.http.server` | Server lifecycle, builder, exception mapping, observation SPI | Everything below |
| `io.waveio.http.routing` | `Router`, `RouteRegistry`, `RouteModule`, `RouteMetadata` | `handler`, `middleware`, `http` |
| `io.waveio.http.handler` | Functional handler contracts (`HttpHandler`, `AsyncHttpHandler`, `StreamingHttpHandler`) | `http` |
| `io.waveio.http.middleware` | Middleware pipeline contracts (`Middleware`, `HandlerChain`) | `http` |
| `io.waveio.http` | HTTP model: `HttpRequest`, `HttpResponse`, `HttpHeaders`, `HttpMethod`, `HttpStatus`, `Cookie` | `body` |
| `io.waveio.http.body` | Message bodies (`RequestBody`, `ResponseBody`, `BodyCodec`) | *None (Root)* |

> [!NOTE]
> All implementation packages under `io.waveio.http.internal.*` are unexported by the module descriptor (`module-info.java`) and remain strictly internal.

---

## 📖 Detailed Guide & Examples

### 1. Route Handlers & Execution Modes

WaveIO provides three execution modes to match workload characteristics:

```java
// 1. Non-blocking (runs on Netty EventLoop - MUST NOT block)
routes.get("/ping", request -> HttpResponse.text("pong"));

// 2. Blocking (runs on Java 21 Virtual Threads)
routes.blockingGet("/database/user/:id", request -> {
    String id = request.requirePathParam("id");
    User user = database.findUserSync(id); // Safe to perform blocking I/O
    return HttpResponse.json(user.toJson());
});

// 3. Asynchronous (returns CompletionStage, completes on any thread pool)
routes.asyncGet("/external-api", request -> {
    return httpClient.sendAsync(remoteRequest)
            .thenApply(res -> HttpResponse.text(res.body()));
});
```

### 2. Route Modules & Grouping

Organize application endpoints without reflection or annotation scanning:

```java
public final class UserRoutes implements RouteModule {
    private final UserHandler handler;

    public UserRoutes(UserHandler handler) {
        this.handler = handler;
    }

    @Override
    public void register(RouteRegistry routes) {
        routes.group("/users", users -> users
                .get("", handler::list)
                .blockingGet("/:id", handler::find)
                .blockingPost("", handler::create));
    }
}

// In server bootstrap:
Router.builder()
        .install(new UserRoutes(userHandler))
        .build();
```

### 3. Request & Response Streaming

WaveIO uses standard JDK `Flow.Publisher<ByteBuffer>` for high-throughput streaming:

```java
// Response streaming (chunked or fixed-length)
routes.get("/stream", request -> HttpResponse.status(HttpStatus.OK)
        .stream(byteBufferPublisher));

// Serving files with virtual-thread streaming (16 KiB chunks)
routes.get("/download", request -> HttpResponse.status(HttpStatus.OK)
        .file(Path.of("large-dataset.csv")));

// Inbound request streaming (uploads)
routes.streamingPost("/upload", (request, bodyPublisher) -> {
    return processUpload(bodyPublisher)
            .thenApply(size -> HttpResponse.text("Received " + size + " bytes"));
});
```

- Inbound stream demand directly drives socket reads.
- Undemanded chunks decoded in the same TCP read batch are buffered safely in arrival order.
- `maxBodySize` enforces payload bounds and responds with `413 Payload Too Large` on overflow.

### 4. Pluggable Body Codec (`BodyCodec`)

WaveIO does not mandate Jackson, Gson, or any specific serializer. Implement `BodyCodec` once to enable object mapping:

```java
public class JacksonBodyCodec implements BodyCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String contentType() { return "application/json; charset=utf-8"; }
    @Override public byte[] encode(Object value) throws Exception { return mapper.writeValueAsBytes(value); }
    @Override public <T> T decode(byte[] body, Class<T> type) throws Exception { return mapper.readValue(body, type); }
}

// Configure codec on builder:
HttpServer.builder()
        .bodyCodec(new JacksonBodyCodec())
        .post("/items", request -> {
            Item item = request.bodyAs(Item.class); // Throws 400 on decode error
            return HttpResponse.value(storedItem);   // Encoded by BodyCodec
        })
        .build();
```

### 5. Hardened Static File Serving

```java
routes.staticFiles("/assets", Path.of("public"));
```

- Verified against `Path.toRealPath()` to prevent directory traversal and symlink escapes.
- Evaluates `If-Modified-Since` and automatically returns `304 Not Modified` when applicable.
- Runs on virtual threads to prevent filesystem calls from blocking the Netty event loop.

### 6. TLS Configuration

```java
HttpServer.builder()
        .port(8443)
        .tls(Path.of("cert.pem"), Path.of("key.pem"))
        .router(router)
        .build()
        .run();
```

WaveIO validates certificate and key paths during `build()` and parses keys before socket binding. Handshake failures release admission permits cleanly.

### 7. Custom Middleware & CORS Pattern

CORS is intentionally not hardcoded because security policies vary by application:

```java
Set<String> ALLOWED_ORIGINS = Set.of("https://example.com", "https://app.example.com");

serverBuilder.use((request, chain) -> {
    var response = chain.next(request);
    return request.headers().first("origin")
            .filter(ALLOWED_ORIGINS::contains)
            .map(origin -> HttpResponse.status(response.status())
                    .header("access-control-allow-origin", origin)
                    .header("vary", "Origin")
                    .body(response.body()))
            .orElse(response);
});
```

### 8. Observability SPI

```java
HttpServer.builder()
        .observe(observation -> {
            String route = observation.pathPattern().orElse("UNMATCHED");
            long millis = observation.elapsed().toMillis();
            RequestObservation.Outcome outcome = observation.outcome(); // SUCCESS, FAILURE, TIMEOUT, DISCONNECTED
            metrics.record(route, outcome, millis);
        });
```

---

## ⚙️ Runtime Invariants & Protocol Semantics

1. **Per-Connection Ordered Exchange Queue**: Every inbound request (valid or malformed) enters an event-loop-owned exchange queue. No response or error bypasses earlier pipelined responses.
2. **AutoRead Gate Coordination**: Socket reads pause for multiple independent reasons (`PENDING_LIMIT` or `INBOUND_STREAM`) and resume only when all reasons are cleared.
3. **`100 Continue` Queue Alignment**: Interim responses are written below `HttpServerCodec` to prevent corrupting Netty's request-method tracking on pipelined `HEAD` requests.
4. **Isolated Observer Callbacks**: Exceptions thrown inside `RequestObserver` or `Flow.Subscriber` are captured and will never disrupt the Netty channel lifecycle.
5. **No External Logging Dependencies**: Internal framework closing reasons and diagnostics are emitted via Java's native `System.Logger` at `DEBUG` and `TRACE` levels.

---

## 🧪 Build & Verification

WaveIO contains a rigorous two-tier test suite (Fast Unit Tests + Real Network Integration Tests):

```bash
# Run unit & embedded channel tests (Surefire)
mvn test

# Run real TCP, TLS, timeout, and lifecycle integration tests (Failsafe)
mvn verify

# Run all tests with Netty's paranoid reference-count leak detection
mvn -Phardening clean verify

# Verify GraalVM Native Image compilation & native tests (requires GraalVM Java 21+)
mvn -Pnative test

# Run JMH benchmark smoke tests
mvn -Pbenchmark -DskipTests verify
```

Benchmark baselines and variance details are documented in [`BENCHMARKS.md`](BENCHMARKS.md).
