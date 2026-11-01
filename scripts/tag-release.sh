#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: ${0##*/} <version>" >&2
  echo "example: ${0##*/} v0.2.0" >&2
  exit 1
fi

version=$1
cd "$(dirname "${BASH_SOURCE[0]}")/.."
googleapis_dir=$PWD/submodules/googleapis

# Backwards compatible point releases (for example Google Ads API v26.1) keep
# the major version directory, so they do not change the version symlink. The
# point release is only recorded in the googleapis commit that updated the
# protos, e.g. "Protos and build files for Google Ads API v26.1", so look it up
# there. Upstream has spelled them with both separators (v26.1 and v25_2), so
# match either and normalize to dots. Fall back to the symlink version when
# there is no such commit, or when the submodule is not checked out.
point_version() {
  local link=$1 symlink_version=$2
  local link_target target rel major found

  link_target=$(readlink "$link")
  target=$({ cd "$(dirname "$link")" && cd "$link_target" && pwd -P; } 2>/dev/null) || {
    printf '%s' "$symlink_version"
    return
  }
  [[ $target == "$googleapis_dir"/* ]] || {
    printf '%s' "$symlink_version"
    return
  }

  rel=${target#"$googleapis_dir"/}
  major=${symlink_version#v}
  major=${major%%.*}
  found=$(
    git -C "$googleapis_dir" log --format=%s -- "$rel" 2>/dev/null |
      grep -m 1 -oE "v${major}[._][0-9]+"
  ) || true

  found=${found:-$symlink_version}
  found=${found##* }
  # point releases were historically spelled v25_2 and are now v26.1
  printf '%s' "${found//_/.}"
}

message=$(
  for dir in clients/*/; do
    client=$(basename "$dir")
    protobuf_dir=$dir/src/main/protobuf
    [[ -d $protobuf_dir ]] || continue

    # each API version is a symlink into the googleapis submodule
    versions=$(
      find "$protobuf_dir" -type l |
        while read -r link; do
          symlink_version=$(basename "$link")
          [[ $symlink_version =~ ^v[0-9] ]] || continue
          point_version "$link" "$symlink_version"
          printf '\n'
        done |
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
