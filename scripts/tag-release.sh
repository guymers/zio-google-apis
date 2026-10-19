#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: ${0##*/} <version>" >&2
  echo "example: ${0##*/} v0.2.0" >&2
  exit 1
fi

version=$1
cd "$(dirname "${BASH_SOURCE[0]}")/.."

message=$(
  for dir in clients/*/; do
    client=$(basename "$dir")
    protobuf_dir=$dir/src/main/protobuf
    [[ -d $protobuf_dir ]] || continue

    # each API version is a symlink into the googleapis submodule
    versions=$(
      find "$protobuf_dir" -type l |
        awk -F/ '$NF ~ /^v[0-9]/ { print $NF }' |
        sort -u |
        awk 'NR > 1 { printf ", " } { printf "%s", $0 }'
    )
    [[ -n $versions ]] || continue

    printf 'zga-%s %s\n' "$client" "$versions"
  done
)

if [[ -z $message ]]; then
  echo "no client protobuf versions found" >&2
  exit 1
fi

printf 'tagging %s with message:\n%s\n' "$version" "$message"
git tag -a "$version" -m "$message"
