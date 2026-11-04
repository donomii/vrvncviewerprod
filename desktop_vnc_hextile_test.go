//go:build darwin && !ios

package main

import (
	"bufio"
	"bytes"
	"image"
	"image/color"
	"strings"
	"testing"
)

func TestHextileCarriesColorsAndClipsEdgeTiles(t *testing.T) {
	encoded := []byte{
		14, 255, 0, 0, 0, 0, 255, 0, 0, 1, 0, 0x10, // Blue background and green 2×1 subrectangle.
		8, 1, 0, 0, // Carry both colors to the second tile.
		31, // Raw edge tile. Other flag bits are ignored.
	}
	encoded = append(encoded, bytes.Repeat([]byte{0, 0, 255, 0}, 16)...)
	encoded = append(encoded,
		26, 0, 0, 255, 0, 1, 255, 0, 0, 0, 0, 0, // Red background and blue subrectangle.
		0, // Carry red to the next tile.
		24, 1, 0, 255, 0, 0, 0, 0,
	)
	frame := image.NewRGBA(image.Rect(0, 0, 35, 19))
	reader := vncReader{input: bufio.NewReader(bytes.NewReader(encoded)), frame: frame}
	paint := hextilePaintLimit
	if err := reader.readHextileRect(image.Rect(2, 1, 35, 18), &paint); err != nil {
		t.Fatal(err)
	}
	red := color.RGBA{R: 255, A: 255}
	green := color.RGBA{G: 255, A: 255}
	blue := color.RGBA{B: 255, A: 255}
	for point, want := range map[image.Point]color.RGBA{
		image.Pt(0, 0): {}, image.Pt(2, 1): green, image.Pt(3, 1): green,
		image.Pt(4, 1): blue, image.Pt(18, 1): green, image.Pt(19, 1): blue,
		image.Pt(34, 16): red, image.Pt(2, 17): blue, image.Pt(18, 17): red, image.Pt(34, 17): green,
	} {
		if got := frame.RGBAAt(point.X, point.Y); got != want {
			t.Errorf("pixel %v = %v, expected %v", point, got, want)
		}
	}
	if paint != hextilePaintLimit-5 {
		t.Fatalf("remaining paint = %d, expected %d", paint, hextilePaintLimit-5)
	}
}

func TestHextileRejectsMalformedTiles(t *testing.T) {
	for _, fixture := range []struct {
		name    string
		width   int
		paint   int
		encoded []byte
		message string
	}{
		{"no background", 1, 16, []byte{0}, "no background"},
		{"background reset after raw", 33, 16, append([]byte{2, 0, 0, 0, 0, 1}, make([]byte, 65)...), "no background"},
		{"foreground reset after colored", 33, 16, []byte{6, 0, 0, 0, 0, 0, 0, 255, 0, 24, 1, 0, 255, 0, 0, 0, 0, 8, 1}, "no foreground"},
		{"truncated geometry", 1, 16, []byte{14, 0, 0, 0, 0, 0, 0, 255, 0, 1, 0}, "EOF"},
		{"colored conflicts with foreground", 1, 16, []byte{20}, "invalid tile flags"},
		{"edge overflow", 1, 16, []byte{14, 0, 0, 0, 0, 0, 0, 255, 0, 1, 0, 0x10}, "outside tile"},
		{"paint budget", 2, 1, []byte{14, 0, 0, 0, 0, 0, 0, 255, 0, 1, 0, 0x10}, "paint exceeds"},
	} {
		t.Run(fixture.name, func(t *testing.T) {
			frame := image.NewRGBA(image.Rect(0, 0, fixture.width, 1))
			reader := vncReader{input: bufio.NewReader(bytes.NewReader(fixture.encoded)), frame: frame}
			if err := reader.readHextileRect(frame.Rect, &fixture.paint); err == nil || !strings.Contains(err.Error(), fixture.message) {
				t.Fatalf("expected %q, got %v", fixture.message, err)
			}
		})
	}
}
