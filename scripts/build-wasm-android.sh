#!/bin/sh
# Builds the smart contract runtime (ubiubi18/idena-wasm) for Android arm64, at the revision of the
# Linux libraries shipped with the pinned wasm binding: build/libidena_wasm_android_arm64.a.
# The Rust version comes from the repository's rust-toolchain.toml.
set -eu
cd "$(dirname "$0")/.."
. scripts/env.sh
WASM_REPO=https://github.com/ubiubi18/idena-wasm
WASM_REV=b686ed84d26b43dfa74ea12a0d3af1b120a50598
TARGET_DIR=${CARGO_TARGET_DIR:-$PWD/build/wasm-target}
mkdir -p build
[ -d build/idena-wasm ] || git clone -q "$WASM_REPO" build/idena-wasm
git -C build/idena-wasm checkout -q "$WASM_REV"
export CARGO_TARGET_DIR=$TARGET_DIR
(cd build/idena-wasm && capped cargo build --locked --release --all-features --target aarch64-linux-android)
cp "$TARGET_DIR/aarch64-linux-android/release/libidena_wasm.a" build/libidena_wasm_android_arm64.a
echo "build/libidena_wasm_android_arm64.a"
