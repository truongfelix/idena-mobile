package mobile

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"math/big"
	"regexp"
	"strings"

	"github.com/idena-network/idena-go/crypto"
	"github.com/idena-network/idena-go/keywords"
)

// The app's key backup: a key in the Idena export format, the hex of the private key encrypted with a password
// (crypto.Encrypt), as dna_exportKey writes it and ImportKey and the desktop take it.

// NewKey makes a new private key and returns it in the export format, encrypted with password. Nothing is
// written: the app saves and checks the backup first, then gives it to ImportKey.
func NewKey(password string) (string, error) {
	if password == "" {
		return "", errors.New("the password is empty")
	}
	key, err := crypto.GenerateKey()
	if err != nil {
		return "", err
	}
	return encryptKey(crypto.FromECDSA(key), password)
}

// ExportKey returns the node key in dir in the export format, encrypted with password. It reads the key file,
// so the node need not run.
func ExportKey(dir string, password string) (string, error) {
	if password == "" {
		return "", errors.New("the password is empty")
	}
	key, err := crypto.LoadECDSA(nodeKeyFile(dir))
	if err != nil {
		return "", err
	}
	return encryptKey(crypto.FromECDSA(key), password)
}

// KeyAddress opens a key in the export format with password and returns its address; nothing is written.
// Spaces and line breaks around the key are ignored, as a file or a paste may hold them.
func KeyAddress(key string, password string) (string, error) {
	data, err := hex.DecodeString(strings.TrimSpace(key))
	if err != nil {
		return "", errors.New("this is not an exported key")
	}
	decrypted, err := crypto.Decrypt(data, password)
	if err != nil {
		return "", errors.New("the password does not open this key")
	}
	defer clear(decrypted)
	ecdsaKey, err := crypto.ToECDSA(decrypted)
	if err != nil {
		return "", errors.New("this is not an exported key")
	}
	return crypto.PubkeyToAddress(ecdsaKey.PublicKey).Hex(), nil
}

// SuggestPassword returns passwordWords words drawn at random from the flip keywords, joined by hyphens: 6 of
// 2,306 words, about 67 bits, as much as a 12-word recovery phrase of 2,048 words.
func SuggestPassword() (string, error) {
	words := passwordWordList()
	if len(words) < 2048 {
		return "", errors.New("the word list is missing")
	}
	picked := make([]string, passwordWords)
	for i := range picked {
		n, err := rand.Int(rand.Reader, big.NewInt(int64(len(words))))
		if err != nil {
			return "", err
		}
		picked[i] = words[n.Int64()]
	}
	return strings.Join(picked, "-"), nil
}

const passwordWords = 6

var simpleWord = regexp.MustCompile(`^[a-z]{3,9}$`)

// passwordWordList is the flip keywords that are one lowercase word of 3 to 9 letters, each once, in the list's
// order.
func passwordWordList() []string {
	var words []string
	seen := map[string]bool{}
	for i := 0; ; i++ {
		keyword, err := keywords.Get(i)
		if err != nil {
			return words
		}
		if simpleWord.MatchString(keyword.Name) && !seen[keyword.Name] {
			seen[keyword.Name] = true
			words = append(words, keyword.Name)
		}
	}
}

func encryptKey(raw []byte, password string) (string, error) {
	defer clear(raw)
	encrypted, err := crypto.Encrypt(raw, password)
	if err != nil {
		return "", err
	}
	return hex.EncodeToString(encrypted), nil
}
