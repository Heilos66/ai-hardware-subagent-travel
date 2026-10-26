#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
source_dir="${AME_SOURCE:-$repo_root/target/ame-validation/source}"
revision=26ce43e2b34be958b84edb4dcf834291ffa820b6
if [[ ! -d "$source_dir/.git" ]]; then
  git clone --branch dev-2.0 --single-branch https://github.com/arrayforward/ame.git "$source_dir"
  git -C "$source_dir" checkout --detach "$revision"
fi
if [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$revision" ]]; then
  echo "AME source must be pinned to $revision; refusing to change existing checkout." >&2
  exit 1
fi
cmake -S "$repo_root/tools/ame-benchmark" -B "$repo_root/target/ame-validation/build" \
  -DAME_SOURCE="$source_dir" -DCMAKE_BUILD_TYPE=Release
cmake --build "$repo_root/target/ame-validation/build" --target ame_kb_worker -j 4
printf 'AME_SOURCE=%s\n' "$source_dir"
