# Release owner handoff (pre-1.0)

This checklist supplies the two external inputs intentionally left outside the local
development work: an immutable compatibility baseline and an owner-controlled signing key.
It does not freeze the Wave 1.0 API.

## Immutable compatibility baseline

Provide a published, non-SNAPSHOT Maven artifact with a fixed SemVer coordinate:

```text
io.wavejava:wave:<immutable-version>
```

The repository and CI must be able to resolve that exact version without credentials stored in
the repository. Record its SHA-256 digest and the repository/release identifier in the release
record. Do not use `LATEST`, `RELEASE`, a timestamp-only selector, or a locally rebuilt artifact.

Verification command:

```text
./mvnw -B -ntp -pl wave -am verify -Pcompatibility \
  "-Dwave.api.baseline=io.wavejava:wave:<immutable-version>"
```

The command must pass against the published artifact. The existing compatibility safety job must
continue to fail closed for missing, mutable, non-Wave, or unresolvable baselines.

## Signed distribution dry-run

Keep the private signing key outside the repository and CI logs. Supply only the detached
signature and corresponding public key to the verifier workspace:

```text
powershell -File ./tools/distribution/create-sha256.ps1 \
  -Archive tools/distribution/target/wave-0.9.0-rc-distribution.zip
powershell -File ./tools/distribution/verify-release.ps1 \
  -Archive tools/distribution/target/wave-0.9.0-rc-distribution.zip \
  -Sha256File tools/distribution/target/wave-0.9.0-rc-distribution.zip.sha256 \
  -Signature tools/distribution/target/wave-0.9.0-rc-distribution.zip.sig \
  -PublicKey tools/distribution/target/release-public.pem
```

Acceptance requires digest and signature verification to pass, while missing, invalid, or
tampered inputs fail closed. Remove temporary private-key material after the dry-run; no key,
signature, or release sidecar belongs in source control.

## Handoff record

Record the exact baseline coordinate, artifact digest, distribution digest, public-key fingerprint,
and verification date in the release system. Only after those records exist should 0.8-T3 and
0.9-T3 be marked complete. The project then requires an explicit product decision before entering
the separate 1.0 API-stability/freeze phase.
