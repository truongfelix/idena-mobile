// Package mobile is the API of the node for the Android app, bound with gomobile. It only uses types
// gomobile supports (strings, booleans, errors); functions that only report an outcome return an empty
// string on success.
//
// The app runs the node in a process of its own: Start runs it in the calling process until that
// process ends, as the desktop app ends the node process to stop it.
package mobile

import (
	"github.com/idena-network/idena-go/crypto"
	"github.com/idena-network/idena-go/node"
)

// Start starts the node with its data in dir, the JSON configuration cfg applied over the mobile defaults, and key
// as its node key: the private key (32 bytes), which the app keeps encrypted (NodeKeys.kt). The node neither reads
// nor writes a key file. It returns when the node runs.
func Start(dir string, cfg string, key []byte) string {
	nodeKey, err := crypto.ToECDSA(key)
	if err != nil {
		return "the node key is not valid"
	}
	// After the key check: a start refused for its key writes nothing in dir.
	keepCrashOutput(dir)
	if res := node.StartMobileNodeWithKey(dir, cfg, nodeKey); res != "started" {
		return res
	}
	return ""
}
