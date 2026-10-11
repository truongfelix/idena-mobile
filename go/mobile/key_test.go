package mobile

import (
	"bytes"
	"encoding/hex"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"github.com/idena-network/idena-go/crypto"
)

func TestNewKeyOpensAsTheSameAddress(t *testing.T) {
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
	opened, err := OpenKey(backup+"\n", "correct horse battery")
	if err != nil {
		t.Fatal(err)
	}
	if len(opened) != 32 || PrivateKeyAddress(opened) != address {
		t.Fatalf("opened %d bytes as %s, backup opens as %s", len(opened), PrivateKeyAddress(opened), address)
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

func TestOpenKeyRefusesAWrongPasswordOrText(t *testing.T) {
	backup, _ := NewKey("the right password")
	if _, err := OpenKey(backup, "the wrong password"); err == nil ||
		err.Error() != "the password does not open this key" {
		t.Fatalf("wrong password: %v", err)
	}
	if _, err := KeyAddress(backup, "the wrong password"); err == nil {
		t.Fatal("KeyAddress took a wrong password")
	}
	// A 31-byte key, encrypted as an export.
	short, _ := crypto.Encrypt(make([]byte, 31), "the right password")
	for _, text := range []string{"", "not hex", "abcd", backup[:len(backup)-2], hex.EncodeToString(short)} {
		if opened, err := OpenKey(text, "the right password"); err == nil {
			t.Fatalf("%q opened as %x", text, opened)
		}
		if address, err := KeyAddress(text, "the right password"); err == nil {
			t.Fatalf("%q opened as %s", text, address)
		}
	}
	if _, err := NewKey(""); err == nil {
		t.Fatal("an empty password was taken")
	}
}

func TestExportKeyBacksUpTheKey(t *testing.T) {
	key, _ := crypto.GenerateKey()
	raw := crypto.FromECDSA(key)
	given := bytes.Clone(raw)
	backup, err := ExportKey(given, "a long password")
	if err != nil {
		t.Fatal(err)
	}
	// The app's copy of the key is left as it is (gomobile passes byte arrays by reference).
	if !bytes.Equal(given, raw) {
		t.Fatal("ExportKey changed the key it was given")
	}
	opened, err := OpenKey(backup, "a long password")
	if err != nil || !bytes.Equal(opened, raw) {
		t.Fatalf("backup opens as %x (%v)", opened, err)
	}
	if _, err := ExportKey(raw, ""); err == nil {
		t.Fatal("an empty password was taken")
	}
	for _, bad := range [][]byte{nil, raw[:31], make([]byte, 32)} {
		if _, err := ExportKey(bad, "a long password"); err == nil {
			t.Fatalf("exported %x", bad)
		}
	}
	if PrivateKeyAddress(nil) != "" || PrivateKeyAddress(raw) != crypto.PubkeyToAddress(key.PublicKey).Hex() {
		t.Fatal("PrivateKeyAddress")
	}
}

func TestReadKeyFileReadsTheNodesKeyFiles(t *testing.T) {
	dir := t.TempDir()
	if _, err := ReadKeyFile(filepath.Join(dir, "nodekey")); err == nil {
		t.Fatal("read a key that is not there")
	}
	key, _ := crypto.GenerateKey()
	file := filepath.Join(dir, "nodekey")
	if err := crypto.SaveECDSA(file, key); err != nil {
		t.Fatal(err)
	}
	read, err := ReadKeyFile(file)
	if err != nil || !bytes.Equal(read, crypto.FromECDSA(key)) {
		t.Fatalf("read %x (%v)", read, err)
	}
	// The official app's key file may end with a line break.
	if err := os.WriteFile(file, []byte(hex.EncodeToString(crypto.FromECDSA(key))+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if read, err := ReadKeyFile(file); err != nil || !bytes.Equal(read, crypto.FromECDSA(key)) {
		t.Fatalf("with a line break: %x (%v)", read, err)
	}
}

func TestStartRefusesAMissingKey(t *testing.T) {
	dir := t.TempDir()
	for _, key := range [][]byte{nil, make([]byte, 31)} {
		if res := Start(dir, "{}", key); res != "the node key is not valid" {
			t.Fatalf("Start with %x: %q", key, res)
		}
	}
	// Nothing was written: no random key file, no data.
	if entries, _ := os.ReadDir(dir); len(entries) != 0 {
		t.Fatalf("Start wrote %v", entries)
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
