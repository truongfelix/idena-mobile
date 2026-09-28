#!/bin/sh
# Builds the app: scripts/build-apk.sh [gradle tasks...]   (default: assembleDebug)
# MEM_MAX and NEED_MB (scripts/env.sh) cap its memory on a machine shared with other work.
set -eu
cd "$(dirname "$0")/.."
. scripts/env.sh
[ $# -eq 0 ] && set -- assembleDebug
[ -f app/libs/idena-node.aar ] || { echo "app/libs/idena-node.aar is missing: run scripts/build-node-aar.sh"; exit 1; }
capped ./gradlew --no-daemon "$@"
