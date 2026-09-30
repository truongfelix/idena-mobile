// Package mobile is the API of the node for the Android app, bound with gomobile. It only uses types
// gomobile supports (strings, booleans, errors); functions that only report an outcome return an empty
// string on success.
//
// The app runs the node in a process of its own: Start runs it in the calling process until that
// process ends, as the desktop app ends the node process to stop it.
package mobile

import (
	"github.com/idena-network/idena-go/config"
	"github.com/idena-network/idena-go/crypto"
	"github.com/idena-network/idena-go/node"
	"os"
	"path/filepath"
)

// Start starts the node with its data in dir and the JSON configuration cfg, which is applied over the
// mobile defaults. It returns when the node runs.
func Start(dir string, cfg string) string {
	if res := node.StartMobileNode(dir, cfg); res != "started" {
		return res
	}
	return ""
}

// ImportKey stores the private key exported by an Idena app or node (hex, encrypted with password) as
// the node key. An existing key is kept in a backup file next to it. The node uses the key from its
// next start.
func ImportKey(dir string, cfg string, key string, password string) string {
	c, err := config.MakeMobileConfig(dir, cfg)
	if err != nil {
		return err.Error()
	}
	if err := c.ProvideNodeKey(key, password, true); err != nil {
		return err.Error()
	}
	return ""
}

// HasKey reports whether the node has a key in dir.
func HasKey(dir string) bool {
	_, err := os.Stat(nodeKeyFile(dir))
	return err == nil
}

// Address returns the address of the node key in dir, or an empty string if there is none.
func Address(dir string) string {
	key, err := crypto.LoadECDSA(nodeKeyFile(dir))
	if err != nil {
		return ""
	}
	return crypto.PubkeyToAddress(key.PublicKey).Hex()
}

// nodeKeyFile is where config.Config.NodeKey keeps the key of a node started by Start.
func nodeKeyFile(dir string) string {
	return filepath.Join(dir, config.DefaultDataDir, "keystore", "nodekey")
}
