#!/bin/bash
# Signs a release APK with the release key, which stays offline with the developer (never in the repository or on
# GitHub):
#   scripts/sign-release.sh <keystore> <unsigned apk> <signed apk>
# Aligns the APK, signs it with the v2 and v3 schemes (v3 allows a later key rotation; minSdk 29 needs no v1),
# checks the signature, then prints the signing certificate's SHA-256 fingerprint (the one the README lists) and the
# signed APK's SHA-256. apksigner asks for the keystore password.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/env.sh

if [ $# -ne 3 ]; then
  echo "usage: $0 <keystore> <unsigned apk> <signed apk>" >&2
  exit 2
fi
keystore=$1
unsigned=$2
signed=$3
build_tools=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -n 1)
aligned=$(mktemp --suffix=.apk)
trap 'rm -f "$aligned"' EXIT

"$build_tools/zipalign" -P 16 -f 4 "$unsigned" "$aligned"
"$build_tools/apksigner" sign --ks "$keystore" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$signed" "$aligned"
"$build_tools/apksigner" verify --verbose --print-certs "$signed" |
  grep -E '^Verified using v[23]|certificate SHA-256 digest'
sha256sum "$signed"
