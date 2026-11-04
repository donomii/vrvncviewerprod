//go:build darwin && !ios

package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"fmt"
	"image"
	"io"
	"net"
	"os"
	"time"
)

const (
	vncDesktopSize = -223
	vncIOTimeout   = 15 * time.Second
	vncTextLimit   = 1 << 20
)

type vncReader struct {
	conn  net.Conn
	input *bufio.Reader
	frame *image.RGBA
}

func readDesktopVNC(ctx context.Context, address string, publish func(image.Image)) error {
	dialer := net.Dialer{Timeout: vncIOTimeout}
	conn, err := dialer.DialContext(ctx, "tcp", address)
	if err != nil {
		return fmt.Errorf("connect to VNC server: %w", err)
	} else {
		defer conn.Close()
		stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
		defer stop()
		reader := vncReader{conn: conn, input: bufio.NewReader(conn)}
		if err := reader.setup(os.Getenv("VRVNC_PASSWORD")); err != nil {
			return fmt.Errorf("initialize VNC connection: %w", err)
		} else {
			return reader.receiveFrames(publish)
		}
	}
}

func (reader *vncReader) setup(password string) error {
	if err := reader.conn.SetDeadline(time.Now().Add(vncIOTimeout)); err != nil {
		return err
	} else if err := negotiateVNC(reader.input, reader.conn, password); err != nil {
		return err
	} else if err := reader.readServerInit(); err != nil {
		return err
	} else {
		// SetPixelFormat requests little-endian 32-bit BGRX. SetEncodings requests Raw and DesktopSize.
		settings := []byte{0, 0, 0, 0, 32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0,
			2, 0, 0, 2, 0, 0, 0, 0}
		resizeEncoding := int32(vncDesktopSize)
		_, err := reader.conn.Write(binary.BigEndian.AppendUint32(settings, uint32(resizeEncoding)))
		return err
	}
}

func (reader *vncReader) readServerInit() error {
	var header [24]byte
	if _, err := io.ReadFull(reader.input, header[:]); err != nil {
		return err
	} else if err := reader.resize(int(binary.BigEndian.Uint16(header[0:2])), int(binary.BigEndian.Uint16(header[2:4]))); err != nil {
		return err
	} else {
		return discardVNCText(reader.input, binary.BigEndian.Uint32(header[20:24]))
	}
}

func (reader *vncReader) resize(width, height int) error {
	if width < 1 || height < 1 || width > maxFrameSide || height > maxFrameSide {
		return fmt.Errorf("VNC framebuffer %dx%d is outside 1–%d pixels per side", width, height, maxFrameSide)
	} else {
		reader.frame = image.NewRGBA(image.Rect(0, 0, width, height))
		return nil
	}
}

func (reader *vncReader) requestFrame(incremental bool) error {
	packet := [10]byte{3}
	if incremental {
		packet[1] = 1
	} else {
		packet[1] = 0
	}
	binary.BigEndian.PutUint16(packet[6:8], uint16(reader.frame.Rect.Dx()))
	binary.BigEndian.PutUint16(packet[8:10], uint16(reader.frame.Rect.Dy()))
	_, err := reader.conn.Write(packet[:])
	return err
}

func (reader *vncReader) receiveFrames(publish func(image.Image)) error {
	if err := reader.requestFrame(false); err != nil {
		return fmt.Errorf("request initial VNC frame: %w", err)
	} else {
		incremental := false
		for {
			if err := reader.readMessage(&incremental, publish); err != nil {
				return fmt.Errorf("read VNC stream: %w", err)
			} else {
				continue
			}
		}
	}
}

func (reader *vncReader) readMessage(incremental *bool, publish func(image.Image)) error {
	var deadline time.Time
	if *incremental {
		// An unchanged desktop can legitimately leave an incremental request pending.
		deadline = time.Time{}
	} else {
		// The first complete frame or a resize refresh has a bounded wait.
		deadline = time.Now().Add(vncIOTimeout)
	}
	if err := reader.conn.SetReadDeadline(deadline); err != nil {
		return err
	} else if kind, err := reader.input.ReadByte(); err != nil {
		return err
	} else if err := reader.conn.SetDeadline(time.Now().Add(vncIOTimeout)); err != nil {
		return err
	} else {
		return reader.handleMessage(kind, incremental, publish)
	}
}

func (reader *vncReader) handleMessage(kind byte, incremental *bool, publish func(image.Image)) error {
	switch kind {
	case 0:
		resized, err := reader.readUpdate()
		if err != nil {
			return err
		} else if resized {
			*incremental = false
			return reader.requestFrame(false)
		} else {
			snapshot := *reader.frame
			snapshot.Pix = append([]byte(nil), snapshot.Pix...)
			publish(&snapshot)
			*incremental = true
			return reader.requestFrame(true)
		}
	case 2:
		return nil // Bell has no payload.
	case 3:
		var header [7]byte
		if _, err := io.ReadFull(reader.input, header[:]); err != nil {
			return err
		} else {
			return discardVNCText(reader.input, binary.BigEndian.Uint32(header[3:]))
		}
	default:
		return fmt.Errorf("unsupported VNC server message %d, expected framebuffer, bell or clipboard", kind)
	}
}

func (reader *vncReader) readUpdate() (bool, error) {
	var header [3]byte
	if _, err := io.ReadFull(reader.input, header[:]); err != nil {
		return false, err
	} else {
		count := int(binary.BigEndian.Uint16(header[1:]))
		return reader.readRectangles(count)
	}
}

func (reader *vncReader) readRectangles(count int) (bool, error) {
	remainingPixels := maxFrameSide * maxFrameSide
	for index := 0; index < count; index++ {
		var header [12]byte
		if _, err := io.ReadFull(reader.input, header[:]); err != nil {
			return false, err
		} else if encoding := int32(binary.BigEndian.Uint32(header[8:])); encoding == vncDesktopSize && index == count-1 {
			return true, reader.resize(int(binary.BigEndian.Uint16(header[4:6])), int(binary.BigEndian.Uint16(header[6:8])))
		} else if encoding != 0 {
			return false, fmt.Errorf("unexpected VNC encoding %d, expected Raw or final DesktopSize", encoding)
		} else {
			x, y := int(binary.BigEndian.Uint16(header[0:2])), int(binary.BigEndian.Uint16(header[2:4]))
			w, h := int(binary.BigEndian.Uint16(header[4:6])), int(binary.BigEndian.Uint16(header[6:8]))
			remainingPixels -= w * h
			if remainingPixels < 0 {
				return false, fmt.Errorf("VNC update exceeded %d raw pixels", maxFrameSide*maxFrameSide)
			} else if err := reader.readRawRect(image.Rect(x, y, x+w, y+h)); err != nil {
				return false, err
			} else {
				continue
			}
		}
	}
	return false, nil
}

func (reader *vncReader) readRawRect(rect image.Rectangle) error {
	if rect.Empty() || !rect.In(reader.frame.Rect) {
		return fmt.Errorf("VNC rectangle %v is outside framebuffer %v", rect, reader.frame.Rect)
	} else {
		for y := rect.Min.Y; y < rect.Max.Y; y++ {
			start := reader.frame.PixOffset(rect.Min.X, y)
			row := reader.frame.Pix[start : start+4*rect.Dx()]
			if _, err := io.ReadFull(reader.input, row); err != nil {
				return err
			} else {
				for i := 0; i < len(row); i += 4 {
					row[i], row[i+2], row[i+3] = row[i+2], row[i], 255
				}
			}
		}
		return nil
	}
}

func discardVNCText(r io.Reader, size uint32) error {
	if size > vncTextLimit {
		return fmt.Errorf("VNC text length %d exceeds %d bytes", size, vncTextLimit)
	} else {
		_, err := io.CopyN(io.Discard, r, int64(size))
		return err
	}
}
