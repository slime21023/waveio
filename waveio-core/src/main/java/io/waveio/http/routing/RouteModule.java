package io.waveio.http.routing;

/**
 * Contract for modular, reusable route definitions.
 *
 * <p>Enables decomposing large web applications into decoupled controllers or resource modules
 * without relying on reflection or classpath scanning.
 *
 * <pre>{@code
 * public final class UserRoutes implements RouteModule {
 *     private final UserService service;
 *
 *     public UserRoutes(UserService service) {
 *         this.service = service;
 *     }
 *
 *     @Override
 *     public void register(RouteRegistry routes) {
 *         routes.group("/users", users -> users
 *                 .get("", this::listUsers)
 *                 .blockingGet("/:id", this::getUser)
 *                 .blockingPost("", this::createUser));
 *     }
 * }
 * }</pre>
 *
 * @see RouteRegistry#install(RouteModule)
 */
@FunctionalInterface
public interface RouteModule {

    /**
     * Registers routes, sub-groups, and middleware on the provided registry.
     *
     * @param routes the target route registry
     */
    void register(RouteRegistry routes);
}
