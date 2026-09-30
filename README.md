# Idena Mobile

An Android app that runs an [Idena](https://idena.io) node on the phone, without Termux: the node runs
inside the app while Sync is on, mainly to sync and to take part in the validation ceremony. Mining is
meant to be delegated to a pool, such as a desktop node.

**Status: test phase.** Tested on a Redmi Note 7 (Android 10, 2.8 GB of RAM). No APK is published yet.

## What it does

- **Node**: idena-go in the app's own process, as a foreground service while Sync is on. The screen shows
  the sync state, peers, block, speed and time left.
- **Account**: identity state and age, mining status, stake and balance, once the node has synced.
- **Next validation**: its date, and the flips still to make, with a link to
  [app.idena.io](https://app.idena.io), where they are made through a rented shared node.
- **Key**: import the private key exported from the Idena app or app.idena.io (encrypted, with its
  password). Sync stays off until a key is imported. The app keeps the key on the phone and out of
  Android backups.
- **Validation**: during the ceremony, the main screen opens it. It waits for the short session (the flips
  load meanwhile), shows the node's flips, then the long session with the keyword check (approve, report
  up to a third of the flips, one best flip). Answers go to the node 10 s before each session ends, or
  when submitted. Not tried in a real ceremony yet.
- **Practice validation**: the short and long sessions with the sample flips of the Idena web app, then
  the results with the right answers and why a flip should be reported.
- **Social** tab: the posts of [idena.social](https://idena.social), read from the phone's own node, with
  their replies and likes, and posting with the identity (a contract call, about 3.5 iDNA for a short
  post). While "Scan blocks" is selected, the tab reads the new blocks up to the node's, then the older
  ones back to the contract's creation (once, kept on the phone), then follows the new blocks. Nothing
  is scanned while the tab is hidden, the node syncs or a validation runs. Not shown yet: media, texts
  stored on IPFS, comments on replies, messages.

Planned: importing the key from its QR code.

## Phone requirements

- Android 10 or newer, 64-bit ARM (arm64-v8a).
- About 3 GB of RAM works: the node uses 150-230 MB while syncing.
- Storage: the chain takes about 4 GB at the top of the chain (September 2026) and grows by roughly
  1 GB a year. The first sync takes a few hours: keep the phone on Wi-Fi and charging.

## Build

Tools: Android SDK (platform 35, build tools 35), Android NDK r27d (27.3.13750724), JDK 17 or newer,
Go 1.26, Rust through rustup (the version comes from idena-wasm's `rust-toolchain.toml`).

```sh
rustup target add aarch64-linux-android
(cd go && go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind)

scripts/build-wasm-android.sh   # contract runtime (Rust): build/libidena_wasm_android_arm64.a
scripts/build-node-aar.sh       # node (Go, gomobile): app/libs/idena-node.aar
scripts/build-apk.sh            # app: app/build/outputs/apk/debug/app-debug.apk

scripts/build-apk.sh testDebugUnitTest   # unit tests
scripts/build-apk.sh assembleRelease     # shrunk release build
```

The scripts expect the SDK in `~/Android/Sdk`. Put other paths in `scripts/env.local.sh` (not committed),
for example `ANDROID_HOME=/opt/android-sdk`. On a machine shared with other work, `MEM_MAX=3500M` and
`NEED_MB=3500` there cap the memory of the build steps (see `scripts/env.sh`).

Release builds are signed with the debug key during the test phase, so they install over debug builds.
A release key comes before any APK is shared: changing the key later means uninstalling the app, which
deletes the chain and the imported key.

`scripts/monitor_node.py` logs the node on a connected phone (block, peers, CPU, memory, battery).

## How it fits together

- `app/`: the Android app (Kotlin, Jetpack Compose).
- `go/`: the Go module bound with gomobile: package `mobile` starts the node, imports the key and decodes
  the contract calls of a block body (for idena.social: the node keeps no index of other accounts'
  transactions, so the app reads the blocks and recovers the senders). It pins
  idena-go to the `mobile-node` branch of truongfelix/idena-go: the ubiubi18 master with the open pull
  requests [#35](https://github.com/ubiubi18/idena-go/pull/35),
  [#36](https://github.com/ubiubi18/idena-go/pull/36) (without it, the node rewrites its whole database at
  each start) and [#39](https://github.com/ubiubi18/idena-go/pull/39) (fast sync on phones), until they
  are merged.
- `go/wasm-binding-android.patch`: Android support for the wasm binding (a link file and build tags),
  applied by `scripts/build-node-aar.sh`.
- The node on the phone: RPC on 127.0.0.1 only, with a random key and no browser access; the IPFS
  "server" profile is off, because its address filters block peers on the home network; the default peer
  limits (not the low-power profile).

## Credits and license

- The node is [idena-go](https://github.com/idena-network/idena-go) with the smart contract runtime
  [idena-wasm](https://github.com/idena-network/idena-wasm), as maintained in the
  [ubiubi18](https://github.com/ubiubi18) fork.
- The practice flips are the sample validation of the Idena web app (app.idena.io), bundled with the
  Idena team's agreement.
- The keyword list (`app/src/main/assets/keywords.json`) is idena-go's `keywords/keywords.json`. The flip
  used by the decoding test comes from the test fixtures of
  [idena-desktop](https://github.com/idena-network/idena-desktop) (MIT).
- License: LGPL-3.0, like idena-go (see `LICENSE`).
