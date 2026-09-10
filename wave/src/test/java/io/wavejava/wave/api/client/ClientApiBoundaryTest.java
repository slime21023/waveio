package io.wavejava.wave.api.client;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Keeps the private Netty transport from leaking through the exported client contracts. */
class ClientApiBoundaryTest {
    private static final List<Class<?>> CONTRACTS = List.of(
            WaveClient.class,
            WaveClient.Builder.class,
            ClientRequest.class,
            ClientRequest.Builder.class,
            ClientResponse.class,
            ClientRequestPool.class,
            ClientRequestPool.Builder.class,
            ClientRequestPool.Snapshot.class,
            RetryPolicy.class,
            RetryPolicy.Builder.class,
            BackoffPolicy.class,
            RedirectPolicy.class,
            RedirectPolicy.Builder.class,
            RedirectPolicy.Mode.class,
            ProxyPolicy.class,
            ClientTlsConfig.class,
            ClientTlsConfig.Builder.class,
            ClientLimitExceededException.class,
            ClientRequestPoolRejectedException.class,
            ClientTimeoutException.class,
            ClientCancelledException.class
    );

    @Test
    void publicSignaturesDoNotExposeTransportTypes() {
        for (var contract : CONTRACTS) {
            var signatureTypes = Stream.concat(
                            Stream.of(contract.getConstructors())
                                    .flatMap(constructor -> Stream.concat(
                                            Stream.of(constructor.getGenericParameterTypes()),
                                            Stream.of(constructor.getGenericExceptionTypes()))),
                            Stream.of(contract.getMethods())
                                    .filter(method -> method.getDeclaringClass().equals(contract))
                                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                                    .flatMap(method -> Stream.concat(
                                            Stream.of(method.getGenericReturnType()),
                                            Stream.concat(
                                                    Stream.of(method.getGenericParameterTypes()),
                                                    Stream.of(method.getGenericExceptionTypes())))))
                    .map(Object::toString)
                    .toList();

            assertFalse(signatureTypes.stream().anyMatch(type -> type.contains("java.net.http.")),
                    () -> contract.getName() + " exposes a JDK HTTP transport type: " + signatureTypes);
            assertFalse(signatureTypes.stream().anyMatch(type -> type.contains("io.netty.")),
                    () -> contract.getName() + " exposes a Netty transport type: " + signatureTypes);
        }
    }
}


