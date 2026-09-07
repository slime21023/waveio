#!/bin/sh
set -eu

base_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
properties="$base_dir/.mvn/wrapper/maven-wrapper.properties"
distribution_url=$(sed -n 's/^distributionUrl=//p' "$properties")
expected_sha256=$(sed -n 's/^distributionSha256Sum=//p' "$properties")
archive_name=${distribution_url##*/}
distribution_name=${archive_name%-bin.zip}
wrapper_home=${MAVEN_WRAPPER_CACHE:-"$base_dir/.mvn/wrapper/dists"}
distribution_home="$wrapper_home/$distribution_name"
archive="$wrapper_home/$archive_name"

if [ ! -x "$distribution_home/bin/mvn" ]; then
  mkdir -p "$wrapper_home"
  if [ ! -f "$archive" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl --fail --location --silent --show-error "$distribution_url" --output "$archive"
    elif command -v wget >/dev/null 2>&1; then
      wget -q "$distribution_url" -O "$archive"
    else
      echo "Maven wrapper needs curl or wget to download Maven." >&2
      exit 1
    fi
  fi
  actual_sha256=$(sha256sum "$archive" | awk '{print $1}')
  if [ "$actual_sha256" != "$expected_sha256" ]; then
    echo "Maven distribution SHA-256 mismatch." >&2
    rm -f "$archive"
    exit 1
  fi
  unzip -q "$archive" -d "$wrapper_home"
fi

exec "$distribution_home/bin/mvn" "$@"
