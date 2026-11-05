//go:build darwin && !ios

package main

import (
	"bufio"
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"image"
	"image/color"
	"strings"
	"testing"
)

func TestZRLEContinuousStream(t *testing.T) {
	var compressed bytes.Buffer
	encoder := zlib.NewWriter(&compressed)
	defer encoder.Close()
	frame := image.NewRGBA(image.Rect(0, 0, 4, 2))
	input := bufio.NewReader(nil)
	reader := vncReader{input: input, frame: frame}
	defer reader.closeZRLE()
	red, green := color.RGBA{R: 255, A: 255}, color.RGBA{G: 255, A: 255}
	// Two three-pixel rows with a new packed byte on each row.
	tile := []byte{2, 0, 0, 255, 0, 255, 0, 0x40, 0xa0}
	for _, x := range []int{0, 1} {
		packet := zrleTestPacket(t, encoder, &compressed, tile)
		input.Reset(bytes.NewReader(packet))
		if err := reader.readZRLERect(image.Rect(x, 0, x+3, 2)); err != nil {
			t.Fatalf("decode rectangle at x=%d: %v", x, err)
		} else {
			want := []color.RGBA{red, green, red, green, red, green}
			for i, expected := range want {
				if got := frame.RGBAAt(x+i%3, i/3); got != expected {
					t.Fatalf("rectangle at x=%d pixel %d = %v, want %v", x, i, got, expected)
				}
			}
		}
	}
}

func TestZRLETileModesAndResize(t *testing.T) {
	var compressed bytes.Buffer
	encoder := zlib.NewWriter(&compressed)
	defer encoder.Close()
	input := bufio.NewReader(nil)
	reader := vncReader{input: input, frame: image.NewRGBA(image.Rect(0, 0, 2, 2))}
	defer reader.closeZRLE()
	red, green := color.RGBA{R: 255, A: 255}, color.RGBA{G: 255, A: 255}
	raw := []byte{0, 0, 0, 255, 0, 255, 0, 0, 0, 255, 0, 255, 0}
	for _, test := range []struct {
		name string
		tile []byte
		want [4]color.RGBA
	}{
		{"raw", raw, [4]color.RGBA{red, green, red, green}},
		{"solid", []byte{1, 0, 255, 0}, [4]color.RGBA{green, green, green, green}},
		{"plain runs", []byte{128, 0, 0, 255, 2, 0, 255, 0, 0}, [4]color.RGBA{red, red, red, green}},
		{"palette runs", []byte{130, 0, 0, 255, 0, 255, 0, 128, 2, 1}, [4]color.RGBA{red, red, red, green}},
		{"raw after resize", raw, [4]color.RGBA{red, green, red, green}},
	} {
		if err := reader.resize(reader.frame.Rect.Dx()+1, 2); err != nil {
			t.Fatal(err)
		}
		input.Reset(bytes.NewReader(zrleTestPacket(t, encoder, &compressed, test.tile)))
		if err := reader.readZRLERect(image.Rect(1, 0, 3, 2)); err != nil {
			t.Fatalf("%s: %v", test.name, err)
		}
		for i, want := range test.want {
			if got := reader.frame.RGBAAt(1+i%2, i/2); got != want {
				t.Fatalf("%s pixel %d = %v, want %v", test.name, i, got, want)
			}
		}
	}
}

func TestZRLERejectsInvalidRectangles(t *testing.T) {
	for _, test := range []struct {
		name string
		tile []byte
		want string
	}{
		{"reserved mode", []byte{129}, "subencoding"},
		{"palette index", []byte{3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xc0}, "palette index"},
		{"run outside tile", []byte{128, 0, 0, 0, 1}, "tile remainder"},
		{"missing pixel bytes", []byte{1, 0, 0}, "EOF"},
		{"surplus pixel bytes", []byte{1, 0, 0, 0, 0}, "after the final tile"},
		{"compressed length", nil, "compressed length"},
	} {
		t.Run(test.name, func(t *testing.T) {
			packet := []byte{255, 255, 255, 255}
			if test.tile != nil {
				var compressed bytes.Buffer
				encoder := zlib.NewWriter(&compressed)
				defer encoder.Close()
				packet = zrleTestPacket(t, encoder, &compressed, test.tile)
			}
			frame := image.NewRGBA(image.Rect(0, 0, 1, 1))
			reader := vncReader{input: bufio.NewReader(bytes.NewReader(packet)), frame: frame}
			defer reader.closeZRLE()
			if err := reader.readZRLERect(frame.Rect); err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("decode returned %v, expected an error containing %q", err, test.want)
			}
		})
	}
}

func zrleTestPacket(t *testing.T, encoder *zlib.Writer, compressed *bytes.Buffer, tile []byte) []byte {
	t.Helper()
	if _, err := encoder.Write(tile); err != nil {
		t.Fatal(err)
	} else if err := encoder.Flush(); err != nil {
		t.Fatal(err)
	}
	packet := binary.BigEndian.AppendUint32(nil, uint32(compressed.Len()))
	packet = append(packet, compressed.Bytes()...)
	compressed.Reset()
	return packet
}
