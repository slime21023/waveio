# Wave security and dependency report (0.9 RC)

This report is generated for each review from the repository state; it is not a claim that a
future release has no vulnerabilities. Record the date, commit, JDK, and Maven version alongside
the output.

## Reproducible checks

```text
./mvnw -B -ntp -pl wave -am dependency:tree -Dverbose
./mvnw -B -ntp -pl wave -am enforcer:enforce
./mvnw -B -ntp -pl wave -am verify -Ptransport
```

The first command records the resolved Netty, Jackson, SLF4J, and test dependencies. Enforcer
checks Maven/Java versions, convergence, and duplicate dependency declarations. The transport
profile runs real sockets with `PARANOID` ByteBuf leak detection.

## Security scope

The 0.7 conformance suite covers HTTP/1.1 request-smuggling framing, HTTP/2 forbidden headers and
frame abuse, trusted forwarded headers, TLS/ALPN, bounded headers/bodies/windows, disconnects, and
shutdown. Static files reject traversal and enforce conditional/range semantics. Reviewers must
attach the relevant test report and dependency tree to a release candidate.

## Review rule

New dependencies require a version in the root BOM/property set, a convergence check, and a short
reason in the roadmap. Vendor-specific integrations are excluded from the 1.0 core.
