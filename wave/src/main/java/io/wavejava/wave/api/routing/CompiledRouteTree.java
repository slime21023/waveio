package io.wavejava.wave.api.routing;

import io.wavejava.wave.api.http.HttpMethod;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable segment tree behind the public {@link Routes} API. */
final class CompiledRouteTree {
    private static final List<String> STANDARD_METHOD_ORDER = List.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    private final Node root;
    private final List<RouteMetadata> metadata;

    private CompiledRouteTree(Node root, List<RouteMetadata> metadata) {
        this.root = root;
        this.metadata = List.copyOf(metadata);
    }

    static CompiledRouteTree compile(Collection<Routes.RouteDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        MutableNode mutableRoot = new MutableNode();
        List<RouteMetadata> metadata = new ArrayList<>(definitions.size());

        for (Routes.RouteDefinition definition : definitions) {
            RouteMetadata routeMetadata = new RouteMetadata(definition.method(), definition.pattern().source());
            MutableNode node = mutableRoot;
            for (RoutePattern.Segment segment : definition.pattern().segments()) {
                node = switch (segment.kind()) {
                    case STATIC -> node.staticChildren.computeIfAbsent(segment.literal(), ignored -> new MutableNode());
                    case PARAMETER -> {
                        if (node.parameterChild == null) {
                            node.parameterChild = new MutableNode();
                        }
                        yield node.parameterChild;
                    }
                    case WILDCARD -> {
                        if (node.wildcardChild == null) {
                            node.wildcardChild = new MutableNode();
                        }
                        yield node.wildcardChild;
                    }
                };
            }

            String methodName = methodName(definition.method());
            Endpoint previous = node.endpoints.putIfAbsent(
                    methodName, new Endpoint(routeMetadata, definition.handler(), definition.pattern()));
            if (previous != null) {
                throw new RouteConfigurationException("ambiguous route registration for " + routeMetadata
                        + "; it conflicts with " + previous.metadata());
            }
            metadata.add(routeMetadata);
        }
        return new CompiledRouteTree(mutableRoot.freeze(), metadata);
    }

    List<RouteMetadata> metadata() {
        return metadata;
    }

    RouteMatch match(HttpMethod method, String path) {
        Objects.requireNonNull(method, "method");
        List<String> segments = requestSegments(path);
        Search search = new Search(method);
        search(root, segments, 0, new ArrayList<>(), search);

        Set<HttpMethod> allowed = search.allowedMethods();
        if (search.selected != null) {
            return RouteMatch.matched(
                    search.selected.endpoint().metadata(),
                    search.selected.endpoint().handler(),
                    search.selected.endpoint().pattern().bind(search.selected.captures()),
                    allowed,
                    search.selected.headFallback());
        }
        if (!search.sawPath) {
            return RouteMatch.notFound();
        }
        if ("OPTIONS".equals(methodName(method))) {
            return RouteMatch.automaticOptions(allowed);
        }
        return RouteMatch.methodNotAllowed(allowed);
    }

    private static void search(Node node, List<String> segments, int index, List<String> captures, Search search) {
        if (index == segments.size()) {
            search.consider(node, captures);
            if (node.wildcardChild != null) {
                captures.add("");
                search(node.wildcardChild, segments, segments.size(), captures, search);
                captures.remove(captures.size() - 1);
            }
            return;
        }

        String segment = segments.get(index);
        Node staticChild = node.staticChildren.get(segment);
        if (staticChild != null) {
            search(staticChild, segments, index + 1, captures, search);
        }
        if (node.parameterChild != null && !segment.isEmpty()) {
            captures.add(segment);
            search(node.parameterChild, segments, index + 1, captures, search);
            captures.remove(captures.size() - 1);
        }
        if (node.wildcardChild != null) {
            captures.add(String.join("/", segments.subList(index, segments.size())));
            search(node.wildcardChild, segments, segments.size(), captures, search);
            captures.remove(captures.size() - 1);
        }
    }

    private static List<String> requestSegments(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isEmpty() || path.charAt(0) != '/') {
            throw new IllegalArgumentException("request path must start with '/': " + path);
        }
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw new IllegalArgumentException("request path must not contain a query or fragment: " + path);
        }
        if ("/".equals(path)) {
            return List.of();
        }
        return List.of(path.substring(1).split("/", -1));
    }

    private static String methodName(HttpMethod method) {
        // HTTP method tokens are case-sensitive. HttpMethod deliberately preserves the wire token,
        // so a custom "get" route must not silently become the standard GET route.
        return method.name();
    }

    private static final class Search {
        private final String requestedMethodName;
        private final Map<String, HttpMethod> methods = new LinkedHashMap<>();
        private Candidate selected;
        private boolean sawPath;

        private Search(HttpMethod requestedMethod) {
            this.requestedMethodName = methodName(requestedMethod);
        }

        private void consider(Node node, List<String> captures) {
            if (node.endpoints.isEmpty()) {
                return;
            }
            sawPath = true;
            for (Endpoint endpoint : node.endpoints.values()) {
                methods.putIfAbsent(methodName(endpoint.metadata().method()), endpoint.metadata().method());
            }

            if (selected != null) {
                return;
            }
            Endpoint direct = node.endpoints.get(requestedMethodName);
            if (direct != null) {
                selected = new Candidate(direct, List.copyOf(captures), false);
                return;
            }
            if ("HEAD".equals(requestedMethodName)) {
                Endpoint get = node.endpoints.get("GET");
                if (get != null) {
                    selected = new Candidate(get, List.copyOf(captures), true);
                }
            }
        }

        private Set<HttpMethod> allowedMethods() {
            if (!sawPath) {
                return Set.of();
            }
            Map<String, HttpMethod> allMethods = new LinkedHashMap<>(methods);
            if (allMethods.containsKey("GET")) {
                allMethods.putIfAbsent("HEAD", HttpMethod.of("HEAD"));
            }
            allMethods.putIfAbsent("OPTIONS", HttpMethod.of("OPTIONS"));

            List<Map.Entry<String, HttpMethod>> ordered = new ArrayList<>(allMethods.entrySet());
            ordered.sort(Comparator
                    .comparingInt((Map.Entry<String, HttpMethod> entry) -> standardMethodRank(entry.getKey()))
                    .thenComparing(Map.Entry::getKey));
            Set<HttpMethod> result = new LinkedHashSet<>();
            for (Map.Entry<String, HttpMethod> entry : ordered) {
                result.add(entry.getValue());
            }
            return Collections.unmodifiableSet(result);
        }

        private static int standardMethodRank(String method) {
            int index = STANDARD_METHOD_ORDER.indexOf(method);
            return index >= 0 ? index : STANDARD_METHOD_ORDER.size();
        }
    }

    private static final class MutableNode {
        private final Map<String, MutableNode> staticChildren = new LinkedHashMap<>();
        private final Map<String, Endpoint> endpoints = new LinkedHashMap<>();
        private MutableNode parameterChild;
        private MutableNode wildcardChild;

        private Node freeze() {
            Map<String, Node> frozenStaticChildren = new LinkedHashMap<>();
            for (Map.Entry<String, MutableNode> entry : staticChildren.entrySet()) {
                frozenStaticChildren.put(entry.getKey(), entry.getValue().freeze());
            }
            return new Node(
                    Collections.unmodifiableMap(frozenStaticChildren),
                    parameterChild == null ? null : parameterChild.freeze(),
                    wildcardChild == null ? null : wildcardChild.freeze(),
                    Collections.unmodifiableMap(new LinkedHashMap<>(endpoints)));
        }
    }

    private record Node(
            Map<String, Node> staticChildren,
            Node parameterChild,
            Node wildcardChild,
            Map<String, Endpoint> endpoints) {
    }

    private record Endpoint(RouteMetadata metadata, Handler handler, RoutePattern pattern) {
        private Endpoint {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(pattern, "pattern");
        }
    }

    private record Candidate(Endpoint endpoint, List<String> captures, boolean headFallback) {
        private Candidate {
            Objects.requireNonNull(endpoint, "endpoint");
            captures = List.copyOf(captures);
        }
    }
}
