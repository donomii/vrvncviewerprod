//go:build darwin && !ios

package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"fmt"
	"image"
	"image/draw"
	"io"
	"net"
	"os"
	"time"
)

const (
	vncCopyRect    = 1
	vncDesktopSize = -223
	vncLastRect    = -224
	vncIOTimeout   = 15 * time.Second
	vncTextLimit   = 1 << 20
)

type vncReader struct {
	conn      net.Conn
	input     *bufio.Reader
	frame     *image.RGBA
	hasPixels bool
	zrle      *zrleDecoder
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
		// Request little-endian 32-bit BGRX and the supported rectangle encodings.
		encodings := [...]int32{vncCopyRect, vncZRLE, vncHextile, 0, vncDesktopSize, vncLastRect}
		settings := []byte{0, 0, 0, 0, 32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0,
			2, 0, 0, byte(len(encodings))}
		for _, encoding := range encodings {
			settings = binary.BigEndian.AppendUint32(settings, uint32(encoding))
		}
		_, err := reader.conn.Write(settings)
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
		next := image.NewRGBA(image.Rect(0, 0, width, height))
		if reader.frame != nil {
			shared := reader.frame.Bounds().Intersect(next.Bounds())
			draw.Draw(next, shared, reader.frame, shared.Min, draw.Src)
		}
		reader.frame = next
		reader.hasPixels = false
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
	defer reader.closeZRLE()
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
		} else if resized || !reader.hasPixels {
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
	remainingPaint := hextilePaintLimit
	resized := false
	for index := 0; index < count; index++ {
		var header [12]byte
		if _, err := io.ReadFull(reader.input, header[:]); err != nil {
			return false, err
		} else if encoding := int32(binary.BigEndian.Uint32(header[8:])); encoding == vncLastRect {
			return resized, nil
		} else if resized {
			return false, fmt.Errorf("VNC DesktopSize can only be followed by LastRect in an update")
		} else if encoding == vncDesktopSize {
			if err := reader.resize(int(binary.BigEndian.Uint16(header[4:6])), int(binary.BigEndian.Uint16(header[6:8]))); err != nil {
				return false, err
			} else {
				resized = true
			}
		} else {
			x, y := int(binary.BigEndian.Uint16(header[0:2])), int(binary.BigEndian.Uint16(header[2:4]))
			w, h := int(binary.BigEndian.Uint16(header[4:6])), int(binary.BigEndian.Uint16(header[6:8]))
			rect := image.Rect(x, y, x+w, y+h)
			remainingPixels -= w * h
			if remainingPixels < 0 {
				return false, fmt.Errorf("VNC update exceeded %d pixels", maxFrameSide*maxFrameSide)
			} else if rect.Empty() || !rect.In(reader.frame.Rect) {
				return false, fmt.Errorf("VNC rectangle %v is outside framebuffer %v", rect, reader.frame.Rect)
			} else if err := reader.readPixelRect(encoding, rect, &remainingPaint); err != nil {
				return false, err
			} else {
				reader.hasPixels = true
			}
		}
	}
	return resized, nil
}

func (reader *vncReader) readPixelRect(encoding int32, rect image.Rectangle, paint *int) error {
	switch encoding {
	case 0:
		return reader.readRawRect(rect)
	case vncCopyRect:
		return reader.readCopyRect(rect)
	case vncHextile:
		return reader.readHextileRect(rect, paint)
	case vncZRLE:
		return reader.readZRLERect(rect)
	default:
		return fmt.Errorf("unexpected VNC encoding %d, expected Raw, CopyRect, Hextile, ZRLE, DesktopSize or LastRect", encoding)
	}
}

func (reader *vncReader) readRawRect(rect image.Rectangle) error {
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

func (reader *vncReader) readCopyRect(rect image.Rectangle) error {
	var coordinates [4]byte
	if !reader.hasPixels {
		return fmt.Errorf("VNC CopyRect needs framebuffer pixels before copying")
	} else if _, err := io.ReadFull(reader.input, coordinates[:]); err != nil {
		return err
	} else {
		x := int(binary.BigEndian.Uint16(coordinates[:2]))
		y := int(binary.BigEndian.Uint16(coordinates[2:]))
		source := image.Rect(x, y, x+rect.Dx(), y+rect.Dy())
		if !source.In(reader.frame.Rect) {
			return fmt.Errorf("VNC CopyRect source %v is outside framebuffer %v", source, reader.frame.Rect)
		} else {
			for i := 0; i < rect.Dy(); i++ {
				row := i
				if rect.Min.Y > y { // Preserve source rows when copying down.
					row = rect.Dy() - 1 - i
				}
				from := reader.frame.PixOffset(x, y+row)
				to := reader.frame.PixOffset(rect.Min.X, rect.Min.Y+row)
				length := 4 * rect.Dx()
				copy(reader.frame.Pix[to:to+length], reader.frame.Pix[from:from+length])
			}
			return nil
		}
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
