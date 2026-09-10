#!/usr/bin/env bash
set -euo pipefail

warmups="${1:-1}"
iterations="${2:-1}"
forks="${3:-1}"
for value in "$warmups" "$iterations" "$forks"; do
  if ! [[ "$value" =~ ^[1-9][0-9]*$ ]] || [ "$value" -gt 10 ]; then
    echo 'Warmups, iterations, and forks must each be between 1 and 10.' >&2
    exit 2
  fi
done

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
output_directory="$root/tools/wave-benchmarks/target"
mkdir -p "$output_directory"
metadata="$output_directory/baseline-metadata.txt"
result="$output_directory/baseline-result.txt"
{
  printf 'date_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'commit=%s\n' "$(git -C "$root" rev-parse HEAD)"
  if [ -n "$(git -C "$root" status --porcelain)" ]; then printf 'worktree_dirty=true\n'; else printf 'worktree_dirty=false\n'; fi
  printf 'jdk='; java -version 2>&1 | head -n 1
  printf 'os=%s\n' "$(uname -a)"
  printf 'cpu_count=%s\n' "$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc)"
  printf 'warmups=%s\niterations=%s\nforks=%s\n' "$warmups" "$iterations" "$forks"
  printf 'jvm_options=-Dwave.reliability.tests=false\n'
} > "$metadata"

cd "$root"
bash ./mvnw -q -B -ntp -f tools/wave-benchmarks/pom.xml dependency:build-classpath \
  -Dmdep.outputFile=target/dependency-classpath.txt
dependency_classpath="$(cat tools/wave-benchmarks/target/dependency-classpath.txt)"
java -cp "tools/wave-benchmarks/target/classes:$dependency_classpath" \
  org.openjdk.jmh.Main WaveRouteBenchmark -wi "$warmups" -i "$iterations" -f "$forks" 2>&1 | tee "$result"
grep -Fq 'Result "' "$result" || {
  echo 'JMH did not produce a successful benchmark result.' >&2
  exit 1
}
if grep -Eq '<forked VM failed|ClassNotFoundException' "$result"; then
  echo 'JMH fork reported a class-loading failure.' >&2
  exit 1
fi
