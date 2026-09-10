package io.wavejava.wave.testing;

import io.wavejava.wave.api.application.WaveApp;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;
import io.wavejava.wave.api.lifecycle.ServiceLifecycle;
import io.wavejava.wave.runtime.ApplicationRuntime;
import java.util.Objects;

/** Test-only in-process access to the unexported application runtime. */
public final class TestApplication {
    private final WaveApp application;

    private TestApplication(WaveApp application) {
        this.application = application;
    }

    /** Dispatches one request without opening a socket. */
    public static Response dispatch(WaveApp application, Request request) {
        return new ApplicationRuntime(Objects.requireNonNull(application, "application"))
                .dispatch(Objects.requireNonNull(request, "request"), ignored -> { })
                .response();
    }

    /** Wraps an application for concise in-process test dispatch. */
    public static TestApplication of(WaveApp application) {
        return new TestApplication(Objects.requireNonNull(application, "application"));
    }

    /** Dispatches one request against this fixture. */
    public Response handle(Request request) {
        return dispatch(application, request);
    }

    /** Creates the lifecycle that a server run would own. */
    public static ServiceLifecycle lifecycle(WaveApp application) {
        return new ApplicationRuntime(Objects.requireNonNull(application, "application")).newServiceLifecycle();
    }
}
