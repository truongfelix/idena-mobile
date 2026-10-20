package mobile

import (
	"bytes"
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
// (crypto.Encrypt), as dna_exportKey writes it and OpenKey and the desktop take it.

// NewKey makes a new private key and returns it in the export format, encrypted with password. Nothing is
// written: the app saves and checks the backup first, then opens it with OpenKey.
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

// ExportKey returns the private key key (32 bytes) in the export format, encrypted with password. key is left as
// it is.
func ExportKey(key []byte, password string) (string, error) {
	if password == "" {
		return "", errors.New("the password is empty")
	}
	if _, err := crypto.ToECDSA(key); err != nil {
		return "", errors.New("the node key is not valid")
	}
	return encryptKey(bytes.Clone(key), password)
}

// OpenKey opens a key in the export format with password and returns its private key (32 bytes): the app keeps it
// encrypted as the node key. Spaces and line breaks around the key are ignored, as a file or a paste may hold them.
func OpenKey(key string, password string) ([]byte, error) {
	data, err := hex.DecodeString(strings.TrimSpace(key))
	if err != nil {
		return nil, errors.New("this is not an exported key")
	}
	decrypted, err := crypto.Decrypt(data, password)
	if err != nil {
		return nil, errors.New("the password does not open this key")
	}
	if _, err := crypto.ToECDSA(decrypted); err != nil {
		clear(decrypted)
		return nil, errors.New("this is not an exported key")
	}
	return decrypted, nil
}

// KeyAddress opens a key in the export format with password and returns its address; nothing is written.
func KeyAddress(key string, password string) (string, error) {
	opened, err := OpenKey(key, password)
	if err != nil {
		return "", err
	}
	defer clear(opened)
	return PrivateKeyAddress(opened), nil
}

// PrivateKeyAddress returns the address of the private key key (32 bytes), or an empty string if it is not one.
func PrivateKeyAddress(key []byte) string {
	ecdsaKey, err := crypto.ToECDSA(key)
	if err != nil {
		return ""
	}
	return crypto.PubkeyToAddress(ecdsaKey.PublicKey).Hex()
}

// PrivateKeyPublicKey returns the public key of the private key key (32 bytes) as the hex of its 65 uncompressed
// bytes (04, then X and Y), the form the node gives an identity's key in: what others encrypt private messages with.
// An empty string if key is not one.
func PrivateKeyPublicKey(key []byte) string {
	ecdsaKey, err := crypto.ToECDSA(key)
	if err != nil {
		return ""
	}
	return hex.EncodeToString(crypto.FromECDSAPub(&ecdsaKey.PublicKey))
}

// ReadKeyFile returns the private key (32 bytes) in a key file the node wrote, the hex of the key: the node key of
// the versions before 0.1.76 and the backups their imports left. The app encrypts it, then deletes the file.
func ReadKeyFile(path string) ([]byte, error) {
	key, err := crypto.LoadECDSA(path)
	if err != nil {
		return nil, err
	}
	return crypto.FromECDSA(key), nil
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
