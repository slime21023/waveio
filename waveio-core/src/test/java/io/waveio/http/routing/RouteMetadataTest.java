package io.waveio.http.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteMetadataTest {
    @Test
    void buildsImmutableNamedMetadataWithAttributes() {
        var source = new java.util.LinkedHashMap<String, String>();
        source.put("operation", "read-user");

        var metadata = RouteMetadata.builder()
                .name("users.show")
                .attributes(source)
                .attribute("audience", "public")
                .build();
        source.put("operation", "changed");

        assertEquals("users.show", metadata.name().orElseThrow());
        assertEquals(Map.of("operation", "read-user", "audience", "public"),
                metadata.attributes());
        assertThrows(UnsupportedOperationException.class,
                () -> metadata.attributes().put("x", "y"));
    }

    @Test
    void validatesNamesAndAttributes() {
        assertEquals(RouteMetadata.empty(), RouteMetadata.builder().build());
        assertThrows(IllegalArgumentException.class,
                () -> RouteMetadata.builder().name(" "));
        assertThrows(IllegalArgumentException.class,
                () -> RouteMetadata.builder().attribute(" ", "value"));
        assertThrows(NullPointerException.class,
                () -> RouteMetadata.builder().attribute("key", null));
    }
}
