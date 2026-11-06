package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"io"
)

// Web Push message encryption (RFC 8291 with the aes128gcm content coding of RFC 8188). The
// UnifiedPush connector gives the app a P-256 public key and a 16-byte auth secret per
// registration; with them the wake-up is unreadable for the distributor (ntfy and friends) and
// accepted by distributors that only forward encrypted messages. Without keys the plain wake-up
// is sent (it holds no list content either).

const (
	webPushRecordSize = 4096
	webPushSaltSize   = 16
	webPushKeySize    = 65
)

var errBadKeys = errors.New("invalid web push keys")

// decodeB64 accepts base64url with or without padding (and standard base64 as a courtesy).
func decodeB64(s string) ([]byte, error) {
	for _, enc := range []*base64.Encoding{base64.RawURLEncoding, base64.URLEncoding, base64.RawStdEncoding, base64.StdEncoding} {
		if b, err := enc.DecodeString(s); err == nil {
			return b, nil
		}
	}
	return nil, errBadKeys
}

// validWebPushKeys checks a registration's keys before they are stored.
func validWebPushKeys(p256dh, auth string) bool {
	pub, err := decodeB64(p256dh)
	if err != nil || len(pub) != webPushKeySize {
		return false
	}
	if _, err := ecdh.P256().NewPublicKey(pub); err != nil {
		return false
	}
	secret, err := decodeB64(auth)
	return err == nil && len(secret) == 16
}

func hkdf(salt, ikm, info []byte, length int) []byte {
	extract := hmac.New(sha256.New, salt)
	extract.Write(ikm)
	prk := extract.Sum(nil)
	expand := hmac.New(sha256.New, prk)
	expand.Write(info)
	expand.Write([]byte{1})
	return expand.Sum(nil)[:length]
}

// encryptWebPush returns the aes128gcm body for plaintext (one record, no padding).
func encryptWebPush(plaintext []byte, p256dh, auth string, random io.Reader) ([]byte, error) {
	uaPublicBytes, err := decodeB64(p256dh)
	if err != nil {
		return nil, err
	}
	authSecret, err := decodeB64(auth)
	if err != nil || len(authSecret) != 16 {
		return nil, errBadKeys
	}
	uaPublic, err := ecdh.P256().NewPublicKey(uaPublicBytes)
	if err != nil {
		return nil, errBadKeys
	}
	asPrivate, err := ecdh.P256().GenerateKey(random)
	if err != nil {
		return nil, err
	}
	salt := make([]byte, webPushSaltSize)
	if _, err := io.ReadFull(random, salt); err != nil {
		return nil, err
	}
	return sealWebPush(plaintext, uaPublic, authSecret, asPrivate, salt)
}

// sealWebPush is the encryption itself. Separate from the random key and salt so that a test can
// check the exact bytes against RFC 8291 Appendix A.
func sealWebPush(plaintext []byte, uaPublic *ecdh.PublicKey, authSecret []byte, asPrivate *ecdh.PrivateKey, salt []byte) ([]byte, error) {
	asPublic := asPrivate.PublicKey().Bytes()
	shared, err := asPrivate.ECDH(uaPublic)
	if err != nil {
		return nil, err
	}
	keyInfo := append(append([]byte("WebPush: info\x00"), uaPublic.Bytes()...), asPublic...)
	ikm := hkdf(authSecret, shared, keyInfo, 32)

	cek := hkdf(salt, ikm, []byte("Content-Encoding: aes128gcm\x00"), 16)
	nonce := hkdf(salt, ikm, []byte("Content-Encoding: nonce\x00"), 12)

	block, err := aes.NewCipher(cek)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	record := append(append([]byte{}, plaintext...), 0x02) // last record, no padding
	sealed := gcm.Seal(nil, nonce, record, nil)

	header := make([]byte, 0, webPushSaltSize+4+1+len(asPublic))
	header = append(header, salt...)
	header = binary.BigEndian.AppendUint32(header, webPushRecordSize)
	header = append(header, byte(len(asPublic)))
	header = append(header, asPublic...)
	return append(header, sealed...), nil
}

// randomReader is crypto/rand in production; tests may replace it.
var randomReader io.Reader = rand.Reader
