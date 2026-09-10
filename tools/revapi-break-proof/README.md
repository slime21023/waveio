# Revapi breaking-change proof

This is a deliberately failing, isolated fixture for the 0.8-T3 compatibility gate. The old
artifact exposes `io.wavejava.fixture.PublicApi.removed()`, while the new artifact removes it.
Revapi must report the binary incompatibility and exit non-zero.

Run from the repository root; a non-zero exit is the expected result:

```text
./mvnw -B -ntp -f tools/revapi-break-proof/pom.xml clean install
```

The fixture is not a Wave release baseline. It only proves that the configured Revapi pipeline
rejects a deliberate public-member removal. The CI safety job checks both the non-zero exit and
the `java.method.removed` diagnostic before treating the proof as passed.
