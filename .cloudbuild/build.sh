#!/bin/bash
# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# Test, package, and build Docker image.
set -eu

cleanup() {
  rm -f jdk.tar.gz
}
trap cleanup EXIT

TRAVEL_BUILD_ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$TRAVEL_BUILD_ROOT"

image_revision=$(git rev-parse HEAD 2>/dev/null || printf 'unknown')
if [ "$image_revision" != unknown ] && ! git diff --quiet HEAD --; then
  image_revision="${image_revision}-dirty"
fi
build_time=${CID_BUILD_TIME:-$(TZ=Asia/Shanghai date '+%Y%m%d%H%M%S')}
image_tag="release-${build_time}"
if [ "${#image_tag}" -gt 128 ] || ! printf '%s\n' "$image_tag" | LC_ALL=C grep -Eq '^[A-Za-z0-9_][A-Za-z0-9_.-]*$'; then
  printf 'image tag is not valid.\n' >&2
  exit 2
fi
image_name="travel-agent:${image_tag}"
image_base=${TRAVEL_BASE_IMAGE:-registry-cbu.huawei.com/hce_docker/hce_x86_64:3.0.2606.1}
image_created=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

# Versions diagnose JDK selection without explicitly dumping machine-specific paths.
if [ -n "${JAVA_HOME:-}" ]; then
  printf 'build_environment JAVA_HOME_configured=true\n'
else
  printf 'build_environment JAVA_HOME_configured=false\n'
fi
java -version 2>&1 || true
javac -version 2>&1 || true
docker --version
docker info --format 'build_environment docker_server={{.ServerVersion}}'

set -- -B -ntp clean verify -Dmaven.test.failure.ignore=true
if [ -n "${MAVEN_REPO_LOCAL:-}" ]; then
  if [ ! -d "$MAVEN_REPO_LOCAL" ]; then
    printf 'MAVEN_REPO_LOCAL does not point to a directory.\n' >&2
    exit 2
  fi
  set -- "$@" "-Dmaven.repo.local=$MAVEN_REPO_LOCAL"
fi
if [ -n "${TRAVEL_MAVEN_SETTINGS:-}" ]; then
  if [ ! -f "$TRAVEL_MAVEN_SETTINGS" ]; then
    printf 'TRAVEL_MAVEN_SETTINGS does not point to a file.\n' >&2
    exit 2
  fi
  settings_path=$(cd -- "$(dirname -- "$TRAVEL_MAVEN_SETTINGS")" && pwd)/$(basename -- "$TRAVEL_MAVEN_SETTINGS")
  set -- "$@" -s "$settings_path"
fi
mvn "$@"

# Package JDK from build environment for Docker context
jdk_dir=$(dirname "$JAVA_HOME")
jdk_base=$(basename "$JAVA_HOME")
tar -czf jdk.tar.gz -C "$jdk_dir" "$jdk_base"

# Docker build
artifact_dir=target/image-artifacts
mkdir -p "$artifact_dir"
docker build \
  --build-arg "RUNTIME_IMAGE=$image_base" \
  --label "org.opencontainers.image.revision=$image_revision" \
  --label "org.opencontainers.image.created=$image_created" \
  -t "$image_name" .
docker image save --output "$artifact_dir/travel-agent-${build_time}.tar" "$image_name"
gzip -n "$artifact_dir/travel-agent-${build_time}.tar"
chmod 600 "$artifact_dir/travel-agent-${build_time}.tar.gz"
printf 'Image archive ready: %s/travel-agent-%s.tar.gz\n' "$artifact_dir" "$build_time"
