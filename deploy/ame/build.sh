#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "$0")" && pwd)"
source_dir="${1:?Usage: build.sh /path/to/ame-source [build-dir]}"
build_dir="${2:-$root/build}"
revision=df68cbf1f2742095901ec97434507d76cba2f61d
[[ "$(git -C "$source_dir" rev-parse HEAD)" == "$revision" ]] || {
  printf '%s\n' 'Unexpected AME revision; use the pinned commit.' >&2
  exit 1
}
[[ -z "$(git -C "$source_dir" status --porcelain --untracked-files=no)" ]] || {
  printf '%s\n' 'AME source has local modifications.' >&2
  exit 1
}
cmake -S "$root" -B "$build_dir" -DAME_SOURCE="$(cd "$source_dir" && pwd)" -DCMAKE_BUILD_TYPE=Release
cmake --build "$build_dir" --target ame_worker --parallel "${BUILD_JOBS:-2}"
