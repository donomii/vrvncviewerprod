//go:build darwin && !ios

package main

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"image"
	"io"
)

const (
	vncZRLE    = 16
	zrleSide   = 64
	zrleRunBit = 128
)

type zrleDecoder struct {
	compressed *vncZlib
	decoded    *bufio.Reader
	tile       [zrleSide * zrleSide]uint32
	palette    [127]uint32
}

func (reader *vncReader) closeZRLE() {
	if reader.zrle == nil {
		return
	} else {
		reader.zrle.compressed.close()
		reader.zrle = nil
	}
}

func (reader *vncReader) readZRLERect(rect image.Rectangle) error {
	var header [4]byte
	if _, err := io.ReadFull(reader.input, header[:]); err != nil {
		return err
	}
	length := int(binary.BigEndian.Uint32(header[:]))
	tiles := ((rect.Dx() + zrleSide - 1) / zrleSide) * ((rect.Dy() + zrleSide - 1) / zrleSide)
	limit := 64 + 5*rect.Dx()*rect.Dy() + 512*tiles
	if length < 1 || length > limit {
		return fmt.Errorf("ZRLE compressed length %d is outside 1–%d bytes for %v", length, limit, rect)
	} else if reader.zrle == nil {
		compressed, err := openVNCZlib(reader.input)
		if err != nil {
			return err
		} else {
			reader.zrle = &zrleDecoder{compressed: compressed, decoded: bufio.NewReaderSize(compressed, 8192)}
		}
	} else if reader.zrle.compressed.finished {
		return fmt.Errorf("ZRLE rectangle after the connection's compressed stream ended")
	}
	return reader.zrle.readRect(reader.frame, rect, length)
}

func (decoder *zrleDecoder) readRect(frame *image.RGBA, rect image.Rectangle, length int) error {
	decoder.compressed.remaining = length
	decoder.decoded.Reset(decoder.compressed)
	for y := rect.Min.Y; y < rect.Max.Y; y += zrleSide {
		for x := rect.Min.X; x < rect.Max.X; x += zrleSide {
			width, height := min(zrleSide, rect.Max.X-x), min(zrleSide, rect.Max.Y-y)
			if err := decoder.readTile(width, height); err != nil {
				return fmt.Errorf("ZRLE tile at (%d, %d): %w", x, y, err)
			} else {
				for row := 0; row < height; row++ {
					start := frame.PixOffset(x, y+row)
					pixels := frame.Pix[start : start+4*width]
					for col, pixel := range decoder.tile[row*width : (row+1)*width] {
						binary.BigEndian.PutUint32(pixels[4*col:], pixel)
					}
				}
			}
		}
	}
	if _, err := decoder.decoded.ReadByte(); err == io.EOF {
		return nil
	} else if err != nil {
		return err
	} else {
		return fmt.Errorf("ZRLE data after the final tile")
	}
}

func (decoder *zrleDecoder) readTile(width, height int) error {
	mode, err := decoder.decoded.ReadByte()
	if err != nil {
		return err
	}
	pixels := decoder.tile[:width*height]
	switch {
	case mode == 0:
		return decoder.readRawTile(pixels)
	case mode == 1:
		if pixel, err := decoder.readColor(); err != nil {
			return err
		} else {
			fillZRLE(pixels, pixel)
			return nil
		}
	case mode <= 16:
		if err := decoder.readPalette(int(mode)); err != nil {
			return err
		} else {
			return decoder.readPackedTile(width, height, int(mode))
		}
	case mode == zrleRunBit:
		return decoder.readRuns(pixels, 0)
	case mode >= zrleRunBit+2:
		colors := int(mode - zrleRunBit)
		if err := decoder.readPalette(colors); err != nil {
			return err
		} else {
			return decoder.readRuns(pixels, colors)
		}
	default:
		return fmt.Errorf("invalid ZRLE subencoding %d", mode)
	}
}

func (decoder *zrleDecoder) readRawTile(pixels []uint32) error {
	var data [zrleSide * zrleSide * 3]byte
	if _, err := io.ReadFull(decoder.decoded, data[:3*len(pixels)]); err != nil {
		return err
	} else {
		for i := range pixels {
			pixels[i] = zrleColor(data[3*i], data[3*i+1], data[3*i+2])
		}
		return nil
	}
}

func (decoder *zrleDecoder) readColor() (uint32, error) {
	var data [3]byte
	_, err := io.ReadFull(decoder.decoded, data[:])
	return zrleColor(data[0], data[1], data[2]), err
}

func zrleColor(blue, green, red byte) uint32 {
	return uint32(red)<<24 | uint32(green)<<16 | uint32(blue)<<8 | 255
}

func fillZRLE(pixels []uint32, color uint32) {
	for i := range pixels {
		pixels[i] = color
	}
}

func (decoder *zrleDecoder) readPalette(colors int) error {
	for i := 0; i < colors; i++ {
		if pixel, err := decoder.readColor(); err != nil {
			return err
		} else {
			decoder.palette[i] = pixel
		}
	}
	return nil
}

func (decoder *zrleDecoder) readPackedTile(width, height, colors int) error {
	bits := 4
	switch {
	case colors == 2:
		bits = 1
	case colors <= 4:
		bits = 2
	}
	mask := (1 << bits) - 1
	for row := 0; row < height; row++ {
		for col := 0; col < width; {
			packed, err := decoder.decoded.ReadByte()
			if err != nil {
				return err
			}
			for shift := 8 - bits; shift >= 0 && col < width; shift -= bits {
				index := int(packed>>shift) & mask
				if index >= colors {
					return fmt.Errorf("ZRLE palette index %d exceeds %d colors", index, colors)
				} else {
					decoder.tile[row*width+col] = decoder.palette[index]
					col++
				}
			}
		}
	}
	return nil
}

func (decoder *zrleDecoder) readRuns(pixels []uint32, colors int) error {
	for len(pixels) != 0 {
		pixel, long, err := decoder.readRunColor(colors)
		if err != nil {
			return err
		}
		length := 1
		if long {
			if length, err = decoder.readRunLength(len(pixels)); err != nil {
				return err
			}
		}
		fillZRLE(pixels[:length], pixel)
		pixels = pixels[length:]
	}
	return nil
}

func (decoder *zrleDecoder) readRunColor(colors int) (uint32, bool, error) {
	if colors == 0 {
		pixel, err := decoder.readColor()
		return pixel, true, err
	} else if packed, err := decoder.decoded.ReadByte(); err != nil {
		return 0, false, err
	} else if index := int(packed & (zrleRunBit - 1)); index >= colors {
		return 0, false, fmt.Errorf("ZRLE palette index %d exceeds %d colors", index, colors)
	} else {
		return decoder.palette[index], packed&zrleRunBit != 0, nil
	}
}

func (decoder *zrleDecoder) readRunLength(capacity int) (int, error) {
	length := 1
	for {
		if next, err := decoder.decoded.ReadByte(); err != nil {
			return 0, err
		} else if length += int(next); length > capacity {
			return 0, fmt.Errorf("ZRLE run of %d pixels exceeds tile remainder %d", length, capacity)
		} else if next != 255 {
			return length, nil
		}
	}
}
