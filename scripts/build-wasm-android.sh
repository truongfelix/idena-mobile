#!/bin/sh
# Builds the smart contract runtime (truongfelix/idena-wasm) for Android arm64: the runtime code of the
# Linux libraries shipped with the pinned wasm binding (b686ed8), with Wasmer fetched from the truongfelix
# fork (same commit 45f9bcc): build/libidena_wasm_android_arm64.a.
# The Rust version comes from the repository's rust-toolchain.toml.
set -eu
cd "$(dirname "$0")/.."
. scripts/env.sh
WASM_REPO=https://github.com/truongfelix/idena-wasm
WASM_REV=d9ce6fcf321d87b6b16776a0f243c4202a1bda6e
TARGET_DIR=${CARGO_TARGET_DIR:-$PWD/build/wasm-target}
mkdir -p build
[ -d build/idena-wasm ] || git clone -q "$WASM_REPO" build/idena-wasm
git -C build/idena-wasm remote set-url origin "$WASM_REPO"
git -C build/idena-wasm fetch -q origin
git -C build/idena-wasm checkout -q "$WASM_REV"
export CARGO_TARGET_DIR=$TARGET_DIR
(cd build/idena-wasm && capped cargo build --locked --release --all-features --target aarch64-linux-android)
cp "$TARGET_DIR/aarch64-linux-android/release/libidena_wasm.a" build/libidena_wasm_android_arm64.a
echo "build/libidena_wasm_android_arm64.a"
