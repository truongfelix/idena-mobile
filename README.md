# Idena Mobile

An Android app that runs an [Idena](https://idena.io) node on your phone: sync, validation, flips, wallet and
Idena Social, with no computer and no Termux. It runs on the same Idena network as the official apps, with the same
rules: no hard fork.

Download: [releases](https://github.com/truongfelix/idena-mobile/releases) (see [Install](#install)). Questions and
problems: [issues](https://github.com/truongfelix/idena-mobile/issues). Community software, without warranty: use it
at your own risk.

## What you can do

- **Run your own node** in the app while Sync is on. A dot in the title row shows its state, and ⓘ gives the
  details. Mine on the phone, or delegate mining to a pool such as a desktop node.
- **Validate** on the phone: the short and long sessions and the keyword check, plus a practice validation with
  sample flips.
- **Make flips** from the camera, the gallery or an optional picture search, and see your flips as validators will.
- **Wallet and identity**: receive (with a QR code), send, add stake, activate an invitation code, and invite others.
- **Idena Social**: [idena.social](https://idena.social) read from your own node, with the feed, threads, profiles,
  contacts and follows, likes, replies, tips and posts. Private messages and group chats too. You get notifications
  of new likes, comments, tips and messages while the app is closed.
- **Oracle votings and ads**: vote, create and run votings, make ads and send them to review.
- **Your key**: create an account, or import the key exported from the Idena app or app.idena.io, and back it up
  encrypted with a password. The app keeps it encrypted on the phone (Android Keystore) and out of Android backups.
- **Node settings** (Node card > Advanced settings): the peer level, the connections, the database write buffers,
  direct peers, and opening the node's port on your router (Wi-Fi).

The app talks only to its own node. The only exceptions are the picture search for flips (Openverse and
Wikimedia) and the check for new versions on GitHub, each only if you turn it on.

## Install

Download the APK from the [releases](https://github.com/truongfelix/idena-mobile/releases) and check it. Its SHA-256
is in the release notes, and its signing certificate's SHA-256 fingerprint must be the one listed there
(`apksigner verify --print-certs <apk>`). Android asks once to allow installs from the browser or the file manager
that opens it.

A new version installs over the previous one and keeps the chain and the key. Uninstalling deletes both, so back up
the key first ("Back up my key") and import it again after a new install.

**Phone requirements:**
- Android 10 or newer, 64-bit ARM (arm64-v8a);
- about 3 GB of RAM;
- about 4 GB of storage for the chain, growing by roughly 1 GB a year.

The first sync takes a few hours: keep the phone on Wi-Fi and charging.

## Build from source

You need the Android SDK (platform and build tools 35), Android NDK r27d (27.3.13750724), JDK 17 or newer, Go 1.26.10
or newer, and Rust through rustup.

```sh
rustup target add aarch64-linux-android
(cd go && go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind)

scripts/build-wasm-android.sh            # contract runtime (Rust)
scripts/build-node-aar.sh                # node (Go, gomobile): app/libs/idena-node.aar
scripts/build-apk.sh                     # debug APK
scripts/build-apk.sh testDebugUnitTest   # unit tests
scripts/build-apk.sh assembleRelease     # release APK, unsigned
```

The scripts expect the SDK in `~/Android/Sdk`. Put other paths in `scripts/env.local.sh` (not committed), for
example `ANDROID_HOME=/opt/android-sdk`.

Release builds are signed with the release key, which stays offline with the developer (`scripts/sign-release.sh`).
An APK signed with another key does not install over the app.

The code: `app/` is the Android app (Kotlin, Jetpack Compose). `go/` is the node, bound with gomobile, which pins
[truongfelix/idena-go](https://github.com/truongfelix/idena-go) and builds the contract runtime from
[truongfelix/idena-wasm](https://github.com/truongfelix/idena-wasm).

## Credits and license

- The node is [idena-go](https://github.com/idena-network/idena-go) with the contract runtime
  [idena-wasm](https://github.com/idena-network/idena-wasm), as maintained in the truongfelix forks, which started
  from the [ubiubi18](https://github.com/ubiubi18) forks.
- The practice flips are the sample validation of the Idena web app (app.idena.io), bundled with the Idena team's
  agreement.
- The keyword list (`app/src/main/assets/keywords.json`) is idena-go's `keywords/keywords.json`. The flip used by the
  decoding test comes from the test fixtures of [idena-desktop](https://github.com/idena-network/idena-desktop) (MIT).
- License: LGPL-3.0, like idena-go (see `LICENSE`; the GNU GPL 3.0 it builds on is in `COPYING`).
