#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
version="${AME_VERSION:-3.0}"
revision="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]]["revision"])' "$repo_root/tools/ame-benchmark/versions.json" "$version")"
branch="dev-$version"
source_dir="${AME_SOURCE:-$repo_root/target/ame-$version/source}"
build_dir="${AME_BUILD_DIR:-$repo_root/target/ame-$version/build}"
if [[ ! -d "$source_dir/.git" ]]; then
  git clone --branch "$branch" --single-branch https://github.com/arrayforward/ame.git "$source_dir"
  git -C "$source_dir" checkout --detach "$revision"
fi
if [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$revision" ]]; then
  echo "AME source must be pinned to $revision; refusing to change existing checkout." >&2
  exit 1
fi
cmake -S "$repo_root/tools/ame-benchmark" -B "$build_dir" \
  -DAME_SOURCE="$source_dir" -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS="-include cstdint"
cmake --build "$build_dir" --target ame_kb_worker -j 4
printf 'AME_SOURCE=%s\nAME_BINARY=%s/ame_kb_worker\n' "$source_dir" "$build_dir"
