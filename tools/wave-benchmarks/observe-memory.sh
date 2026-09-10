#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "usage: $0 <jvm-pid> [duration-seconds]" >&2
  exit 2
fi
target_pid="$1"
duration="${2:-30}"
if ! [[ "$target_pid" =~ ^[1-9][0-9]*$ ]] || ! [[ "$duration" =~ ^[1-9][0-9]*$ ]] || [ "$duration" -gt 600 ]; then
  echo 'pid must be positive and duration must be between 1 and 600 seconds.' >&2
  exit 2
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
output="$root/tools/wave-benchmarks/target/memory-observation.txt"
mkdir -p "$(dirname "$output")"
: > "$output"
for ((elapsed=0; elapsed<duration; elapsed+=5)); do
  printf '\n--- sample elapsed=%ss utc=%s ---\n' "$elapsed" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" | tee -a "$output"
  jcmd "$target_pid" GC.heap_info | tee -a "$output"
  jcmd "$target_pid" VM.native_memory summary | tee -a "$output"
  sleep 5
done
echo "memory samples written to $output"
