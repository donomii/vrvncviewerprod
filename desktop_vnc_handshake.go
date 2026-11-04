//go:build darwin && !ios

package main

import (
	"bytes"
	"crypto/des"
	"encoding/binary"
	"fmt"
	"io"
	"math/bits"
)

const (
	vncNoAuth       = 1
	vncPasswordAuth = 2
)

func negotiateVNC(r io.Reader, w io.Writer, password string) error {
	var version [12]byte
	if _, err := io.ReadFull(r, version[:]); err != nil {
		return err
	} else if minor, err := vncVersion(version); err != nil {
		return err
	} else if _, err := fmt.Fprintf(w, "RFB 003.%03d\n", minor); err != nil {
		return err
	} else if err := authenticateVNC(r, w, minor, password); err != nil {
		return err
	} else {
		_, err := w.Write([]byte{1}) // ClientInit with shared desktop access.
		return err
	}
}

func vncVersion(version [12]byte) (int, error) {
	minor := 0
	for _, digit := range version[8:11] {
		if digit < '0' || digit > '9' {
			return 0, fmt.Errorf("invalid RFB protocol version %q", version)
		} else {
			minor = 10*minor + int(digit-'0')
		}
	}
	if string(version[:8]) != "RFB 003." || version[11] != '\n' || minor < 3 {
		return 0, fmt.Errorf("unsupported RFB version %q, expected 3.3 or later in the 3.x series", version)
	} else if minor >= 8 {
		return 8, nil
	} else if minor == 7 {
		return 7, nil
	} else {
		return 3, nil
	}
}

func authenticateVNC(r io.Reader, w io.Writer, minor int, password string) error {
	var wanted byte
	if password != "" {
		wanted = vncPasswordAuth
	} else {
		wanted = vncNoAuth
	}
	if err := selectVNCSecurity(r, w, minor, wanted); err != nil {
		return err
	} else if wanted == vncPasswordAuth {
		if err := answerVNCChallenge(r, w, password); err != nil {
			return err
		} else {
			return checkVNCSecurityResult(r)
		}
	} else if minor >= 8 {
		return checkVNCSecurityResult(r)
	} else {
		return nil // RFB 3.3 and 3.7 omit SecurityResult for None.
	}
}

func selectVNCSecurity(r io.Reader, w io.Writer, minor int, wanted byte) error {
	if minor == 3 {
		var security uint32
		if err := binary.Read(r, binary.BigEndian, &security); err != nil {
			return err
		} else if security != uint32(wanted) {
			return vncSecurityUnavailable(wanted)
		} else {
			return nil
		}
	} else {
		var count [1]byte
		if _, err := io.ReadFull(r, count[:]); err != nil {
			return err
		} else {
			offered := make([]byte, count[0])
			if _, err := io.ReadFull(r, offered); err != nil {
				return err
			} else if !bytes.Contains(offered, []byte{wanted}) {
				return vncSecurityUnavailable(wanted)
			} else {
				_, err := w.Write([]byte{wanted})
				return err
			}
		}
	}
}

func vncSecurityUnavailable(wanted byte) error {
	if wanted == vncNoAuth {
		return fmt.Errorf("VNC server does not offer unauthenticated access. Set VRVNC_PASSWORD if it uses VNC password authentication")
	} else {
		return fmt.Errorf("VNC server does not offer classic VNC password authentication")
	}
}

func checkVNCSecurityResult(r io.Reader) error {
	var status uint32
	if err := binary.Read(r, binary.BigEndian, &status); err != nil {
		return err
	} else if status != 0 {
		return fmt.Errorf("VNC server rejected authentication with status %d", status)
	} else {
		return nil
	}
}

func answerVNCChallenge(r io.Reader, w io.Writer, password string) error {
	var key [8]byte
	copy(key[:], password)
	for i, octet := range key {
		key[i] = bits.Reverse8(octet) // RFB uses the reversed bit order for each password byte.
	}
	var challenge [16]byte
	if _, err := io.ReadFull(r, challenge[:]); err != nil {
		return err
	} else if cipher, err := des.NewCipher(key[:]); err != nil {
		return err
	} else {
		cipher.Encrypt(challenge[:8], challenge[:8])
		cipher.Encrypt(challenge[8:], challenge[8:])
		_, err := w.Write(challenge[:])
		return err
	}
}
