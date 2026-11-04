//go:build darwin && !ios

package main

import (
	"bufio"
	"bytes"
	"encoding/hex"
	"fmt"
	"image"
	"image/color"
	"io"
	"net"
	"strings"
	"time"
)

func desktopVNCSelfTest() error {
	if err := checkVNCChallenge(); err != nil {
		return err
	} else if err := checkVNCOlderHandshakes(); err != nil {
		return err
	} else {
		return checkVNCFrames()
	}
}

func checkVNCOlderHandshakes() error {
	for _, fixture := range []struct{ version, offer, reply string }{
		{"RFB 003.003\n", "\x00\x00\x00\x01", "\x01"},
		{"RFB 003.007\n", "\x01\x01", "\x01\x01"},
	} {
		var reply bytes.Buffer
		err := negotiateVNC(strings.NewReader(fixture.version+fixture.offer), &reply, "")
		if err != nil || reply.String() != fixture.version+fixture.reply {
			return fmt.Errorf("VNC handshake for %q got %q and %v", fixture.version, reply.String(), err)
		} else {
			continue
		}
	}
	return nil
}

func checkVNCChallenge() error {
	// Standard DES known-answer vector with each key byte bit-reversed for RFB.
	challenge, _ := hex.DecodeString("0123456789abcdef0123456789abcdef")
	password, _ := hex.DecodeString("c82cea9ed93dfb8f")
	var response bytes.Buffer
	if err := answerVNCChallenge(bytes.NewReader(challenge), &response, string(password)); err != nil {
		return err
	} else if got := hex.EncodeToString(response.Bytes()); got != "85e813540f0ab40585e813540f0ab405" {
		return fmt.Errorf("VNC challenge response %s differs from the DES known-answer vector", got)
	} else {
		return nil
	}
}

func checkVNCFrames() error {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	if err := server.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
		return err
	} else {
		serverResult := make(chan error, 1)
		go func() {
			err := serveVNCCheck(server)
			if err != nil {
				_ = server.Close()
				serverResult <- err
			} else {
				serverResult <- nil
			}
		}()
		reader := vncReader{conn: client, input: bufio.NewReader(client)}
		if err := reader.setup(""); err != nil {
			return fmt.Errorf("fixture VNC handshake: %w", err)
		} else {
			var frames []image.Image
			err := reader.receiveFrames(func(frame image.Image) { frames = append(frames, frame) })
			return checkVNCFixtureResult(frames, err, <-serverResult)
		}
	}
}

func serveVNCCheck(conn net.Conn) error {
	steps := []struct{ send, expect []byte }{
		{[]byte("RFB 003.008\n"), []byte("RFB 003.008\n")},
		{[]byte{1, 1}, []byte{1}},
		{[]byte{0, 0, 0, 0}, []byte{1}},
		{[]byte{0, 2, 0, 1, 32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0, 0, 0, 0, 0},
			[]byte{0, 0, 0, 0, 32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0, 2, 0, 0, 3, 0, 0, 0, 1, 0, 0, 0, 0, 255, 255, 255, 33, 3, 0, 0, 0, 0, 0, 0, 2, 0, 1}},
		{[]byte{0, 0, 0, 0}, []byte{3, 0, 0, 0, 0, 0, 0, 2, 0, 1}},
		// First update fills both pixels. Second update changes only the right pixel.
		{[]byte{0, 0, 0, 1, 0, 0, 0, 0, 0, 2, 0, 1, 0, 0, 0, 0, 0, 0, 255, 0, 255, 0, 0, 0}, []byte{3, 1, 0, 0, 0, 0, 0, 2, 0, 1}},
		{[]byte{0, 0, 0, 1, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 255, 0, 0}, []byte{3, 1, 0, 0, 0, 0, 0, 2, 0, 1}},
		// Copy the green right pixel to the left without sending pixel data.
		{[]byte{0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0}, []byte{3, 1, 0, 0, 0, 0, 0, 2, 0, 1}},
		{[]byte{0, 0, 0, 1, 0, 0, 0, 0, 0, 3, 0, 1, 255, 255, 255, 33}, []byte{3, 0, 0, 0, 0, 0, 0, 3, 0, 1}},
		{[]byte{0, 0, 0, 0}, []byte{3, 0, 0, 0, 0, 0, 0, 3, 0, 1}},
		// The server supplies only the new pixel. The previous two should stay green.
		{[]byte{0, 0, 0, 1, 0, 2, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0, 255, 0, 0, 0}, []byte{3, 1, 0, 0, 0, 0, 0, 3, 0, 1}},
		{[]byte{0, 0, 0, 1, 0, 3, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0}, nil},
	}
	for index, step := range steps {
		if _, err := conn.Write(step.send); err != nil {
			return fmt.Errorf("write VNC fixture step %d: %w", index, err)
		} else {
			got := make([]byte, len(step.expect))
			if _, err := io.ReadFull(conn, got); err != nil || !bytes.Equal(got, step.expect) {
				return fmt.Errorf("VNC fixture step %d got %x, expected %x (read error %v)", index, got, step.expect, err)
			} else {
				continue
			}
		}
	}
	return nil
}

func checkVNCFixtureResult(frames []image.Image, streamErr, serverErr error) error {
	red, blue, green := color.RGBA{R: 255, A: 255}, color.RGBA{B: 255, A: 255}, color.RGBA{G: 255, A: 255}
	if serverErr != nil {
		return serverErr
	} else if streamErr == nil || !strings.Contains(streamErr.Error(), "outside framebuffer") || len(frames) != 4 {
		return fmt.Errorf("expected four valid VNC frames and an out-of-bounds rejection, got %d frames and %v", len(frames), streamErr)
	} else if frames[0].At(0, 0) != red || frames[0].At(1, 0) != blue || frames[1].At(0, 0) != red || frames[1].At(1, 0) != green {
		return fmt.Errorf("VNC fixture did not preserve the first snapshot and the unchanged pixel across the update")
	} else if frames[2].At(0, 0) != green || frames[2].At(1, 0) != green {
		return fmt.Errorf("VNC fixture did not copy the green pixel across the framebuffer")
	} else if frames[2].Bounds().Dx() != 2 || frames[3].Bounds().Dx() != 3 ||
		frames[3].At(0, 0) != green || frames[3].At(1, 0) != green || frames[3].At(2, 0) != blue {
		return fmt.Errorf("VNC fixture did not preserve the overlapping pixels after a partial resize update")
	} else {
		return nil
	}
}
