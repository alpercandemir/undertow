#!/bin/sh
# Separate publisher process receives a repository-scoped token and revocable write permit.
set -eu
image=$1
source_dir=$2
manifest=$3
artifact=$4
repository=$5
pr=$6
details=$7
bot_login=$8
bot_id=$9
shift 9
receipt=$1
recovery=$2
permit_url=$3
tenant=$4
review=$5
fence=$6
case "$image" in *@sha256:*) ;; *) echo 'A digest-pinned publisher image is required' >&2; exit 2;; esac
receipt_dir=$(dirname "$receipt")
mkdir -p "$receipt_dir"
exec docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges \
  --pids-limit 128 --memory 512m --cpus 1 --user "$(id -u):$(id -g)" \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m --network undertow-publisher \
  --env GITHUB_TOKEN --env UNDERTOW_PUBLICATION_PERMIT \
  --mount "type=bind,source=$source_dir,target=/input/source.git,readonly" \
  --mount "type=bind,source=$manifest,target=/input/manifest.json,readonly" \
  --mount "type=bind,source=$artifact,target=/input/artifact.json,readonly" \
  --mount "type=bind,source=$receipt_dir,target=/output" \
  "$image" service-publisher --repo /input/source.git --manifest /input/manifest.json \
  --artifact /input/artifact.json --repository "$repository" --pr "$pr" --details-url "$details" \
  --bot-login "$bot_login" --bot-id "$bot_id" --receipt /output/receipt.json --recovery="$recovery" \
  --permit-url "$permit_url" --tenant "$tenant" --review "$review" --fence "$fence"
