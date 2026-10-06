#!/bin/sh
# Operator-owned runner. No App, user-session, database, or publisher credential is mounted.
set -eu
image=$1
mode=$2
model_env=$3
replay=$4
source_dir=$5
manifest=$6
output=$7
case "$image" in *@sha256:*) ;; *) echo 'A digest-pinned worker image is required' >&2; exit 2;; esac
case "$mode" in collect|replay|live) ;; *) exit 2;; esac
mkdir -p "$output"
set -- docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges \
  --pids-limit 128 --memory 1g --cpus 2 --user "$(id -u):$(id -g)" \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m --network undertow-worker \
  --mount "type=bind,source=$source_dir,target=/input/source.git,readonly" \
  --mount "type=bind,source=$manifest,target=/input/manifest.json,readonly" \
  --mount "type=bind,source=$output,target=/output"
if [ "$mode" = live ]; then
  # Reject any env file that would accidentally give this worker global authority.
  awk -F= '/^[[:space:]]*(#|$)/ {next} $1 != "GEMINI_API_KEY" {exit 1}' "$model_env"
  set -- "$@" --env-file "$model_env"
fi
if [ "$mode" = replay ]; then
  set -- "$@" --mount "type=bind,source=$replay,target=/input/replay.json,readonly"
fi
set -- "$@" "$image" service-worker --repo /input/source.git --manifest /input/manifest.json --output /output --mode "$mode"
if [ "$mode" = replay ]; then set -- "$@" --replay /input/replay.json; fi
exec "$@"
