# Wave 0.9 RC distribution

This archive is a reproducible packaging smoke, not a signed release. It contains the Wave jar,
the current architecture/roadmap/API-boundary records, operations and migration documentation,
and consumer/benchmark instructions. The jar is still the current published-style snapshot until
an owner publishes an immutable SemVer coordinate.

Build after compiling the framework:

```text
./mvnw -B -ntp -pl wave -am package -DskipTests
./mvnw -B -ntp -f tools/distribution/pom.xml clean package
```

The archive must not contain credentials, private keys, test fixtures, `target/` directories, or
implementation source. Use `jar tf target/wave-0.9.0-rc-distribution.zip` to inspect its finite
manifest before attaching it to an RC record.

The outer archive and Wave JAR use the fixed `wave.distribution.outputTimestamp` value so repeated
builds from identical inputs can be compared by SHA-256. This does not make the snapshot coordinate
immutable; publication, signing, and the Revapi baseline remain owner actions.

Generate the release digest sidecar with `create-sha256.ps1` (or `create-sha256.sh` on Unix-like
CI runners) before handing the archive to the owner-controlled signing step.
