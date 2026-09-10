package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.wavejava.wave.api.lifecycle.Service;
import io.wavejava.wave.api.lifecycle.ServiceContext;
import io.wavejava.wave.spi.ProviderContext;
import io.wavejava.wave.spi.SpiCatalog;
import io.wavejava.wave.spi.SpiConfigurationException;
import io.wavejava.wave.spi.lifecycle.ServiceProvider;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Assembly tests proving SPI validation occurs before any server listener can bind. */
class SpiCatalogIntegrationTest {
    @Test
    void providerServiceCreatesAFreshLifecycleServiceForEachServerRun() {
        var creations = new AtomicInteger();
        var catalog = SpiCatalog.builder().service(new ServiceProvider() {
            @Override
            public String id() {
                return "test.fresh-service";
            }

            @Override
            public Service create(ProviderContext context) {
                var generation = creations.incrementAndGet();
                return new Service() {
                    @Override
                    public String id() {
                        return "test.fresh-service-" + generation;
                    }

                    @Override
                    public Set<String> dependencies() {
                        return Set.of();
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<Void> start(ServiceContext serviceContext) {
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<Void> stop() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
            }
        }).build();
        var app = Wave.app().providers(catalog).build();

        assertEquals(1, app.newServiceLifecycle().services().size());
        assertEquals(1, app.newServiceLifecycle().services().size());
        assertEquals(2, creations.get());
    }

    @Test
    void equalPriorityProviderConflictFailsDuringApplicationAssembly() {
        var providerOne = new TestServiceProvider("one");
        var providerTwo = new TestServiceProvider("two");

        assertThrows(SpiConfigurationException.class, () -> SpiCatalog.builder()
                .service(providerOne)
                .service(providerTwo)
                .build());
    }

    private record TestServiceProvider(String id) implements ServiceProvider {
        @Override
        public String selectionKey() {
            return "test-service";
        }

        @Override
        public Service create(ProviderContext context) {
            throw new AssertionError("conflicting provider must never be created");
        }
    }
}
