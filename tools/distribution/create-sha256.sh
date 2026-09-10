#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "usage: $0 <archive> [sidecar]" >&2
  exit 2
fi
archive="$1"
sidecar="${2:-$archive.sha256}"
if [ ! -f "$archive" ]; then
  echo "archive does not exist: $archive" >&2
  exit 1
fi
if [ "$archive" = "$sidecar" ]; then
  echo 'SHA-256 sidecar must not overwrite the archive.' >&2
  exit 1
fi

hash="$(sha256sum "$archive" | awk '{print $1}')"
printf '%s  %s\n' "$hash" "$(basename "$archive")" > "$sidecar"
printf 'Wrote SHA-256 sidecar: %s\nsha256=%s\n' "$sidecar" "$hash"
