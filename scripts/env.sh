# Toolchain environment for the build scripts, sourced by them from the repository root.
# Machine-specific values go in scripts/env.local.sh (not committed), sourced first, e.g.
# ANDROID_HOME=/opt/android-sdk, or MEM_MAX and NEED_MB (below) on a machine shared with other work.
if [ -f scripts/env.local.sh ]; then . scripts/env.local.sh; fi
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.3.13750724}
# The app's minimum Android version (Android 10).
export ANDROID_API=${ANDROID_API:-29}
NDK_BIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
export PATH=$HOME/go/bin:$HOME/.cargo/bin:$ANDROID_HOME/platform-tools:$NDK_BIN:$PATH

# Rust cross-compilation (cc crate and linker) for aarch64-linux-android.
export CC_aarch64_linux_android=$NDK_BIN/aarch64-linux-android${ANDROID_API}-clang
export CXX_aarch64_linux_android=$NDK_BIN/aarch64-linux-android${ANDROID_API}-clang++
export AR_aarch64_linux_android=$NDK_BIN/llvm-ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$NDK_BIN/aarch64-linux-android${ANDROID_API}-clang

# Waits until NEED_MB megabytes of memory are available (when NEED_MB is set).
wait_for_memory() {
    [ -n "${NEED_MB:-}" ] || return 0
    while [ "$(awk '/^MemAvailable:/ {print int($2 / 1024)}' /proc/meminfo)" -lt "$NEED_MB" ]; do
        echo "$(date +%T) waiting for $NEED_MB MB of free memory"
        sleep 30
    done
}

# Runs a command; with MEM_MAX set (e.g. 3500M), in a systemd user scope capped at that memory,
# without swap, and as the kernel's first victim if memory runs out.
capped() {
    wait_for_memory
    if [ -n "${MEM_MAX:-}" ]; then
        systemd-run --user --scope --quiet -p MemoryMax="$MEM_MAX" -p MemorySwapMax=0 choom -n 1000 -- "$@"
    else
        "$@"
    fi
}
