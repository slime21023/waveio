package io.wavejava.wave.runtime;

import io.wavejava.wave.api.application.WaveApp;
import io.wavejava.wave.api.http.ExceptionMapper;
import io.wavejava.wave.api.http.HttpException;
import io.wavejava.wave.api.http.Problem;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;
import io.wavejava.wave.api.http.ResponseState;
import io.wavejava.wave.api.lifecycle.ServiceContext;
import io.wavejava.wave.api.lifecycle.ServiceLifecycle;
import io.wavejava.wave.api.middleware.Middleware;
import io.wavejava.wave.api.middleware.Outcome;
import io.wavejava.wave.api.routing.RouteMatch;
import io.wavejava.wave.internal.http.InternalResponse;
import io.wavejava.wave.spi.ProviderContext;
import io.wavejava.wave.spi.SpiCatalog;
import io.wavejava.wave.spi.SpiConfigurationException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runtime execution for one immutable {@link WaveApp}. */
public final class ApplicationRuntime implements RequestDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationRuntime.class);

    private final WaveApp application;

    public ApplicationRuntime(WaveApp application) {
        this.application = Objects.requireNonNull(application, "application");
    }

    /** Creates the one-shot lifecycle used by one server run. */
    public ServiceLifecycle newServiceLifecycle() {
        var services = new ArrayList<>(application.services());
        var providerContext = new ProviderContext(application.config(), application.registry());
        for (var provider : application.providers().serviceProviders()) {
            try {
                services.add(Objects.requireNonNull(provider.create(providerContext),
                        () -> "Service provider " + provider.id() + " returned null"));
            } catch (SpiConfigurationException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new SpiConfigurationException(
                        "Could not create lifecycle service from provider '" + provider.id() + "'", failure);
            }
        }
        return ServiceLifecycle.builder()
                .context(new ServiceContext(application.config(), application.registry()))
                .addAll(services)
                .build();
    }

    @Override
    public ApplicationResult dispatch(Request request, Consumer<String> routeObserver) {
        var response = new InternalResponse();
        return dispatch(Objects.requireNonNull(request, "request"), response, routeObserver);
    }

    private ApplicationResult dispatch(Request request, Response response, Consumer<String> routeObserver) {
        Objects.requireNonNull(routeObserver, "routeObserver");
        var entered = new ArrayList<Middleware>();
        Request activeRequest = request;
        Outcome outcome = Outcome.success();
        var routePattern = "<middleware>";
        try {
            var continueToRoute = true;
            for (var current : application.middleware()) {
                entered.add(current);
                current.onRequest(activeRequest, response);
                if (response.isCommitted()) {
                    continueToRoute = false;
                    break;
                }
            }
            if (continueToRoute) {
                var match = application.routes().match(activeRequest);
                routePattern = routeLabel(match);
                routeObserver.accept(routePattern);
                if (!match.isMatched()) {
                    writeRoutingResponse(match, response);
                } else {
                    activeRequest = activeRequest.withPathParameters(match.pathParameters());
                    for (var current : entered) {
                        current.onRoute(activeRequest, match, response);
                        if (response.isCommitted()) {
                            continueToRoute = false;
                            break;
                        }
                    }
                    if (continueToRoute) {
                        match.requireHandler().handle(activeRequest, response);
                        if (response.state() == ResponseState.OPEN) {
                            throw new IllegalStateException("handler returned without committing a response");
                        }
                    }
                }
            }
        } catch (Exception failure) {
            outcome = Outcome.failure(Outcome.Kind.APPLICATION_FAILURE, failure);
            mapFailure(failure, activeRequest, response);
        } finally {
            for (var index = entered.size() - 1; index >= 0; index--) {
                try {
                    entered.get(index).onResponse(activeRequest, response, outcome);
                } catch (RuntimeException observerFailure) {
                    LOGGER.warn("Middleware onResponse hook failed", observerFailure);
                }
            }
        }
        return new ApplicationResult(response, outcome, routePattern);
    }

    private static String routeLabel(RouteMatch match) {
        return match.route().map(metadata -> metadata.pathPattern()).orElseGet(() -> switch (match.kind()) {
            case NOT_FOUND -> "<not-found>";
            case METHOD_NOT_ALLOWED -> "<method-not-allowed>";
            case AUTOMATIC_OPTIONS -> "<automatic-options>";
            case MATCHED -> throw new IllegalStateException("matched route metadata was absent");
        });
    }

    private static void writeRoutingResponse(RouteMatch match, Response response) {
        switch (match.kind()) {
            case NOT_FOUND -> response.problem(Problem.of(404, "Not Found"));
            case METHOD_NOT_ALLOWED -> response.header("Allow", match.allowHeader())
                    .problem(Problem.of(405, "Method Not Allowed"));
            case AUTOMATIC_OPTIONS -> response.header("Allow", match.allowHeader()).status(204);
            case MATCHED -> throw new IllegalArgumentException("matched routes require handler dispatch");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void mapFailure(Exception failure, Request request, Response response) {
        if (response.state() != ResponseState.OPEN) {
            LOGGER.warn("Request failed after response commitment", failure);
            return;
        }
        try {
            ExceptionMapper<Exception> mapper = null;
            for (var registration : application.exceptionMappers()) {
                if (registration.exceptionType().isInstance(failure)) {
                    mapper = (ExceptionMapper) registration.mapper();
                    break;
                }
            }
            if (mapper != null) {
                mapper.map(failure, request, response);
            } else if (failure instanceof HttpException httpException) {
                response.problem(httpException.problem());
            } else {
                response.problem(Problem.of(500, "Internal Server Error"));
            }
            if (response.state() == ResponseState.OPEN) {
                throw new IllegalStateException("exception mapper returned without committing a response");
            }
        } catch (Exception mapperFailure) {
            LOGGER.error("Exception mapper failed", mapperFailure);
            if (response.state() == ResponseState.OPEN) {
                response.problem(Problem.of(500, "Internal Server Error"));
            }
        }
    }
}
