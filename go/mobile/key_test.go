package mobile

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"github.com/idena-network/idena-go/config"
	"github.com/idena-network/idena-go/crypto"
)

func TestNewKeyImportsAsTheSameAddress(t *testing.T) {
	backup, err := NewKey("correct horse battery")
	if err != nil {
		t.Fatal(err)
	}
	address, err := KeyAddress(backup, "correct horse battery")
	if err != nil {
		t.Fatal(err)
	}
	// A backup read from a file or pasted may end with a line break.
	if again, err := KeyAddress(" "+backup+"\r\n", "correct horse battery"); err != nil || again != address {
		t.Fatalf("with spaces around: %q, %v", again, err)
	}
	dir := t.TempDir()
	if HasKey(dir) {
		t.Fatal("a key before the import")
	}
	// The node's own import (dna_importKey and the desktop use the same function).
	if res := ImportKey(dir, "{}", backup, "correct horse battery"); res != "" {
		t.Fatal(res)
	}
	if got := Address(dir); got != address {
		t.Fatalf("imported %s, backup opens as %s", got, address)
	}
}

func TestNewKeyIsNewEachTime(t *testing.T) {
	a, _ := NewKey("password one")
	b, _ := NewKey("password one")
	addressA, _ := KeyAddress(a, "password one")
	addressB, _ := KeyAddress(b, "password one")
	if addressA == "" || addressA == addressB {
		t.Fatalf("two keys with one address: %s %s", addressA, addressB)
	}
}

func TestKeyAddressRefusesAWrongPasswordOrText(t *testing.T) {
	backup, _ := NewKey("the right password")
	if _, err := KeyAddress(backup, "the wrong password"); err == nil ||
		err.Error() != "the password does not open this key" {
		t.Fatalf("wrong password: %v", err)
	}
	for _, text := range []string{"", "not hex", "abcd", backup[:len(backup)-2]} {
		if address, err := KeyAddress(text, "the right password"); err == nil {
			t.Fatalf("%q opened as %s", text, address)
		}
	}
	if _, err := NewKey(""); err == nil {
		t.Fatal("an empty password was taken")
	}
}

func TestExportKeyBacksUpTheNodeKey(t *testing.T) {
	dir := t.TempDir()
	if _, err := ExportKey(dir, "a long password"); err == nil {
		t.Fatal("exported a key that is not there")
	}
	key, _ := crypto.GenerateKey()
	file := nodeKeyFile(dir)
	if err := os.MkdirAll(filepath.Dir(file), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := crypto.SaveECDSA(file, key); err != nil {
		t.Fatal(err)
	}
	backup, err := ExportKey(dir, "a long password")
	if err != nil {
		t.Fatal(err)
	}
	address, err := KeyAddress(backup, "a long password")
	if err != nil || address != crypto.PubkeyToAddress(key.PublicKey).Hex() {
		t.Fatalf("backup opens as %s (%v)", address, err)
	}
	if _, err := ExportKey(dir, ""); err == nil {
		t.Fatal("an empty password was taken")
	}
	// The node reads the same file the export read.
	c, err := config.MakeMobileConfig(dir, "{}")
	if err != nil {
		t.Fatal(err)
	}
	nodeKey, err := c.NodeKey()
	if err != nil || crypto.PubkeyToAddress(nodeKey.PublicKey).Hex() != address {
		t.Fatalf("the node's key differs: %v", err)
	}
}

func TestSuggestPassword(t *testing.T) {
	words := passwordWordList()
	if len(words) != 2306 {
		t.Fatalf("%d words", len(words))
	}
	seen := map[string]bool{}
	for i := 0; i < 20; i++ {
		password, err := SuggestPassword()
		if err != nil {
			t.Fatal(err)
		}
		if !regexp.MustCompile(`^[a-z]{3,9}(-[a-z]{3,9}){5}$`).MatchString(password) {
			t.Fatalf("%q", password)
		}
		seen[password] = true
	}
	if len(seen) < 20 {
		t.Fatal("the same password twice in 20")
	}
	if !strings.Contains(strings.Join(words, " "), "people") {
		t.Fatal("the flip keywords are not the list")
	}
}
