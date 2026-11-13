#!/bin/sh
# Builds the smart contract runtime (truongfelix/idena-wasm) for Android arm64: the runtime code of the
# Linux libraries shipped with the pinned wasm binding (same runtime code as e2f8f9d, which built them), with Wasmer fetched from the truongfelix
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
# Rust's static libraries carry LTO sections (.llvmbc, .llvmcmd), and Go 1.26.11 refuses any cgo library that
# does ("disallowed LTO section"): they are removed, as truongfelix/idena-wasm's scripts/build-binding-lib.sh does.
out=build/libidena_wasm_android_arm64.a
cp "$TARGET_DIR/aarch64-linux-android/release/libidena_wasm.a" "$out.tmp"
"$NDK_BIN/llvm-objcopy" --remove-section=.llvmbc --remove-section=.llvmcmd "$out.tmp"
# The section list is read on its own, so that a failed read stops the script instead of reading as "none left".
"$NDK_BIN/llvm-objdump" --section-headers "$out.tmp" > "$out.sections"
grep -q '^Sections:' "$out.sections" || { echo "llvm-objdump listed no section of $out.tmp" >&2; exit 1; }
left=$(grep -cE '\.llvmbc|\.llvmcmd|\.gnu\.lto_|\.gnu\.debuglto_|\.llvm\.lto' "$out.sections" || true)
[ "$left" = 0 ] || { echo "$left LTO sections left in $out.tmp" >&2; exit 1; }
rm "$out.sections"
mv "$out.tmp" "$out"
echo "build/libidena_wasm_android_arm64.a"
