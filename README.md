# Idena Mobile

An Android app that runs an [Idena](https://idena.io) node on the phone, without Termux: the node runs
inside the app while Sync is on, mainly to sync and to take part in the validation ceremony. Mining is
meant to be delegated to a pool, such as a desktop node.

**Status: beta.** Test builds are on the [releases page](https://github.com/truongfelix/idena-mobile/releases)
(see [Install](#install)). Tested on two phones, the smaller one with Android 10 and 2.8 GB of RAM.

## What it does

- **Title row**: "Idena", the Node | Social switch, and the status as a colored dot with the peer count
  (green synced with peers; orange starting, looking for peers, syncing, or a background setting to fix;
  red no peers after 20 min, no answer or a wrong clock; grey sync off); ⓘ gives the details.
- **Node** (the last card of the Node tab): idena-go in the app's own process, as a foreground service while
  Sync is on; mining on this phone (a switch, under the mining status, which also names the pool the identity
  is delegated to). A line warns when battery optimization may stop the node with the screen off; Fix shows
  the buttons to Android's setting and to the autostart and battery pages some phones add. The node keeps its
  IPFS port (40405) fixed, so that a port opened for it on the router keeps matching: outside nodes can then
  connect to the phone. The card's **Advanced settings** (folded until opened) come in three groups: Mining
  (delegate to a pool, stop the delegation), Connections (the router port, below; up to 3 direct peers, nodes
  kept connected beyond the slots, with the phone's own peer code to share; the peer level and the IPFS
  connections) and Storage (the write buffers):
  - **Peer level**: Eco (up to 7 Idena peers, idena-go's low-power numbers), Normal (default, up to 18,
    idena-go's defaults) or Hub (up to 30: twice the incoming slots, for nodes that reach the phone through
    the router port).
  - **IPFS connections**: 20, 50 (default), 100 or No limit. Idena peers ride on these connections, so a
    limit must leave room for the level's peers: 20 needs Eco, 50 Eco or Normal (the app raises a limit that
    is too low when the level changes).
  - The **write buffers** of the node's two databases, each on its own: the chain database (the blockchain)
    and the IPFS database (what the network uses to find content; it writes the most once outside nodes can
    reach the phone). Each offers Default (idena-go's 4 MiB), 16, 32 or 64 MiB: a higher level needs more
    RAM but writes less to the phone's storage (chain database on the test phone: 3.1 MiB written a block at
    4 MiB, 0.7 at 32 MiB).

  The node takes these settings when it starts, and the card offers a restart (with a warning close to a
  validation, none during it).
- **Router port** (on Wi-Fi, in the advanced settings' Connections): the node's process looks for the router by UPnP (a search,
  then the router's own announcements for up to 16 minutes, since some routers such as the Livebox never answer
  a search and announce themselves only every ~15 minutes; a router found once is remembered and found again at
  once); Open… opens the node's port (40405) for 6 hours, 1, 3 or 7 days, or until the next validation +
  2 h. The router keeps the opening until then, also after the phone leaves that Wi-Fi;
  Close removes it at once. An opening made outside the app for the phone shows too, with Close. While the
  node runs it keeps the opening every 5 minutes: made again for a new address of the phone or after the
  router lost it, renewed near the end of a router lease shorter than the choice (not during a validation),
  and made again once if no peer connected from outside within 5 minutes of a change (the node's log has
  them). Any change to an opening drops the peers connected at that moment for a minute or two (measured on
  a Livebox), so the app changes it only when needed. Only the router is contacted, on the local network.
- **Profile** (the first card of the Node tab): identity state and age, the address with its robot avatar;
  the next validation (below); the invitation that applies: for an address without an identity, an
  **invitation code** field (activation, no fee), an invitation sent to the address to accept, or the
  invitations to send (below); then My flips, Practice validation, History, Oracle votings (with how many
  wait for the identity's vote) and My ads.
- **Wallet**: Receive (the address and its QR code), Send (to an address or a contact; the node's fee
  estimate, then a confirmation), Add stake (the stake after, how much of it the identity keeps if it
  misses or fails the next validation, the network's minimum stake, that stake cannot be withdrawn).
- **Invitations** (in the Profile, for Verified and Human identities, or with invitations sent): invitations left; invite
  someone to a code given privately (copy or QR; the app keeps it until the next validation) or to an
  address; the invitations of the epoch with their status (not activated, candidate, validated, cancelled);
  cancel one (fee shown first); spoil a code shared publicly.
- **Next validation** (in the Profile): its date with the time left (to the minute within a day of it), the
  task, and the flips: how many are made, what missing ones cost, and Make a flip while some are missing.
- **My flips**: the flips of the epoch, each with its four pictures, words and state, shown as validators
  will see it on a tap; delete one (fee shown first: the node counts a delete as about 120 KB). **Make a
  flip**: an unused keyword pair, four pictures (camera, gallery, or an opt-in image search), an
  anti-AI frame in a random place, the shuffled story, then the node encrypts and publishes it. A flip in progress
  is kept as a draft of its keyword pair. Flips are sent only while the node is synced, and until the flip
  lottery (5 minutes before the validation).
- **Oracle votings** (from the Profile): the votings of the identity's committees to vote in, ongoing and
  closed, and every voting, from the node's own index; a voting's question, times, committee, rewards and
  options. **Vote**: an option, then a confirmation with the payment and the fee; the secret vote is sent at
  once, and the node keeps the vote itself and sends it at the end of the secret voting, so sync must be on
  then. **Create** a voting (title, description, options, times, rewards), then, as on the desktop: launch it,
  add funds, prolong it, finish it and pay out, terminate it, each when the contract allows it.
- **Key**: create a new account, or import the private key exported from the Idena app or app.idena.io
  (encrypted, with its password); "Back up my key" exports it encrypted with a password. Sync stays off until
  there is a key. The app keeps the key encrypted on the phone (with a key held by the Android Keystore) and out
  of Android backups.
- **Validation**: the "Open the validation" button from the flip lottery on. It waits for the short session
  (the flips load meanwhile), shows the node's flips (extra flips replace the ones that did not load, as on
  the desktop), then the long session with the keyword check (the story on the left; on the right the two
  keywords large, the reports used and left, the approvals and the best, then Approve / Report / Best; a tap
  on a picture shows it full screen; report up to a third of the flips, one best flip: a new best asks before
  the previous one goes back to approved). The bottom marks each flip: a green ✓ once solved, then ✓ approved,
  a red ✗ reported, a gold ★ the best. Previous / Next move between the flips; Submit (and Check keywords)
  only shows once every flip that loaded is answered (in the keyword check: every flip with keywords approved
  or reported, and the best marked), and asks to confirm. As on the desktop, the keyword check does not go
  back to the stories. Answers go to the node 10 s before each session ends, whatever is done, or when
  submitted, and are sent again if the node refuses them while the session lasts. After the long answers, the
  screen says when they are in blocks: from then on the app may close.
- **Practice validation**: the short and long sessions with the sample flips of the Idena web app, then
  the results with the right answers and why a flip should be reported.
- **Social** tab: [idena.social](https://idena.social) read from the phone's own node, laid out like
  Reddit. The feed: one line per author (avatar, name, state, time), three lines of text, pictures kept
  small, pills ♡ 💬 🪙 and 👁 (who liked and tipped); sorts (newest, latest activity, most liked, commented
  or tipped over a period), a Following filter and a search (people and addresses as rows, the posts,
  replies and comments found as in the feed, with their pills). The title row, search and options slide away
  while the feed scrolls down. A thread shows the post, then its replies and comments nested by what they
  answer; a tap on a comment's rail (or header) folds it with everything under it. Profiles (identity, or
  "Not validated"; posts, replies, comments, likes and tips given, media), Contacts (private names, Follow,
  export and import), the Inbox (notifications of likes, comments and tips on the identity's posts).
  With the identity: post (an image as WebP without the photo's metadata, the text on IPFS if chosen),
  like, reply, comment, tip, as idena.social writes them; every fee is shown before sending. "Scan blocks"
  reads the new blocks, then the older ones back to the contract's creation (once, kept on the phone);
  nothing is scanned while the node syncs or a validation runs. While the app is not on screen, a notification
  (and a badge on the app's icon) counts the new likes, comments and tips on the identity's posts; opening the
  Social tab removes it. Not done yet: private messages.
- **Ads**: Idena ads for this identity and a note from the developer: a banner on the Node tab, a card
  after each 5 posts of the feed, and under 2 of each 5 posts opened (picked at random). An ad shows when
  coins were burnt for it over the last day, its author's profile lists it, and the oracles' review vote
  approved it; its audience (language, Android, age, stake) must include the identity. **My ads** (from the
  Profile): make an ad, send it to the oracles' review, then show it by burning coins. Everything comes
  from the phone's own node. A tap shows the ad, who paid for it, and its link, opened only on request.

The app talks only to its own node; the one exception is the opt-in image search for flips, which asks
Openverse and Wikimedia directly from the phone.

Planned: importing the key from its QR code, private messages.

## Install

Download the APK from the [releases page](https://github.com/truongfelix/idena-mobile/releases) and check it:
its SHA-256 is in the release notes, and its signing certificate's SHA-256 fingerprint must be the one listed
there (`apksigner verify --print-certs <apk>`, from the Android SDK build tools). Android asks once to allow
installs from the browser or the file manager that opens it.

A new version installs over the previous one and keeps the chain and the key. Uninstalling deletes both: back up
the key first ("Back up my key" on the Key card), and import it again after a new install.

## Phone requirements

- Android 10 or newer, 64-bit ARM (arm64-v8a).
- About 3 GB of RAM works: the node uses 150-230 MB while syncing.
- Storage: the chain takes about 4 GB at the top of the chain (September 2026) and grows by roughly
  1 GB a year. The first sync takes a few hours: keep the phone on Wi-Fi and charging.

## Build

Tools: Android SDK (platform 35, build tools 35), Android NDK r27d (27.3.13750724), JDK 17 or newer,
Go 1.26.9 or newer, Rust through rustup (the version comes from idena-wasm's `rust-toolchain.toml`).

```sh
rustup target add aarch64-linux-android
(cd go && go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind)

scripts/build-wasm-android.sh   # contract runtime (Rust): build/libidena_wasm_android_arm64.a
scripts/build-node-aar.sh       # node (Go, gomobile): app/libs/idena-node.aar
scripts/build-apk.sh            # app: app/build/outputs/apk/debug/app-debug.apk

scripts/build-apk.sh testDebugUnitTest   # unit tests
scripts/build-apk.sh assembleRelease     # shrunk release build, unsigned
scripts/sign-release.sh <keystore> app/build/outputs/apk/release/app-release-unsigned.apk <signed apk>
```

The scripts expect the SDK in `~/Android/Sdk`. Put other paths in `scripts/env.local.sh` (not committed),
for example `ANDROID_HOME=/opt/android-sdk`. On a machine shared with other work, `MEM_MAX=3500M` and
`NEED_MB=3500` there cap the memory of the build steps (see `scripts/env.sh`).

Release builds are unsigned and not debuggable; `scripts/sign-release.sh` signs one with the release key,
which stays offline with the developer. An APK signed with another key does not install over the app: changing
the key means uninstalling it, which deletes the chain and the imported key.

`scripts/monitor_node.py` logs the node on a connected phone (block, peers, CPU, memory, battery).

## How it fits together

- `app/`: the Android app (Kotlin, Jetpack Compose).
- `go/`: the Go module bound with gomobile: package `mobile` starts the node, imports the key and decodes
  the contract calls of a block body (for idena.social: the node keeps no index of other accounts'
  transactions, so the app reads the blocks and recovers the senders). It pins idena-go to the master of
  [truongfelix/idena-go](https://github.com/truongfelix/idena-go), with its dependency forks (wasm binding,
  kad-dht); `scripts/build-wasm-android.sh` builds the contract runtime from
  [truongfelix/idena-wasm](https://github.com/truongfelix/idena-wasm) (Wasmer from truongfelix/wasmer).
- `go/wasm-binding-android.patch`: Android support for the wasm binding (a link file and build tags),
  applied by `scripts/build-node-aar.sh`.
- The node on the phone: RPC on 127.0.0.1 only, with a random key and no browser access; the IPFS
  "server" profile is off, because its address filters block peers on the home network; the peer limits and
  the IPFS connection manager's limits come from Advanced settings (by default idena-go's peer limits and
  50 IPFS connections).

## Credits and license

- The node is [idena-go](https://github.com/idena-network/idena-go) with the smart contract runtime
  [idena-wasm](https://github.com/idena-network/idena-wasm), as maintained in the truongfelix forks,
  which started from the [ubiubi18](https://github.com/ubiubi18) forks.
- The practice flips are the sample validation of the Idena web app (app.idena.io), bundled with the
  Idena team's agreement.
- The keyword list (`app/src/main/assets/keywords.json`) is idena-go's `keywords/keywords.json`. The flip
  used by the decoding test comes from the test fixtures of
  [idena-desktop](https://github.com/idena-network/idena-desktop) (MIT).
- License: LGPL-3.0, like idena-go (see `LICENSE`; the GNU GPL 3.0 it builds on is in `COPYING`).
