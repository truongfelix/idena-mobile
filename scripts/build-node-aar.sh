#!/bin/sh
# Builds the node library of the app, app/libs/idena-node.aar: gomobile bind of go/mobile for arm64.
# Needs build/libidena_wasm_android_arm64.a (scripts/build-wasm-android.sh), gomobile and gobind
# at the version go/go.mod pins (from go/: go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind).
set -eu
cd "$(dirname "$0")/.."
. scripts/env.sh
BINDING_REPO=https://github.com/ubiubi18/idena-wasm-binding
BINDING_REV=01ccca5cc3c94917725964541954a9f20e3412e9
[ -f build/libidena_wasm_android_arm64.a ] || { echo "run scripts/build-wasm-android.sh first"; exit 1; }

# The wasm binding pinned by idena-go, with Android support (go/wasm-binding-android.patch) and the
# Android library. go/go.mod points to it.
rm -rf build/wasm-binding
git clone -q "$BINDING_REPO" build/wasm-binding
git -C build/wasm-binding checkout -q "$BINDING_REV"
patch -s -p1 -d build/wasm-binding < go/wasm-binding-android.patch
cp build/libidena_wasm_android_arm64.a build/wasm-binding/lib/

# -checklinkname=0: github.com/wlynxg/anet (libp2p's Android network interface workaround) needs it.
mkdir -p app/libs
cd go
capped gomobile bind -target android/arm64 -androidapi "$ANDROID_API" -javapkg io.github.truongfelix.idenanode.go \
    -trimpath -ldflags "-checklinkname=0 -s -w" -o ../app/libs/idena-node.aar ./mobile
echo "app/libs/idena-node.aar"
