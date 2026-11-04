//go:build darwin && !ios

package main

import (
	"bytes"
	"context"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"image/jpeg"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/textproto"
	"time"
)

func desktopDemoJPEG() ([]byte, error) {
	frame := image.NewRGBA(image.Rect(0, 0, 320, 180))
	draw.Draw(frame, frame.Bounds(), image.NewUniform(color.RGBA{R: 20, G: 70, B: 150, A: 255}), image.Point{}, draw.Src)
	draw.Draw(frame, image.Rect(20, 20, 150, 160), image.NewUniform(color.RGBA{R: 242, G: 40, B: 36, A: 255}), image.Point{}, draw.Src)
	draw.Draw(frame, image.Rect(170, 20, 300, 160), image.NewUniform(color.RGBA{R: 22, G: 220, B: 92, A: 255}), image.Point{}, draw.Src)
	var encoded bytes.Buffer
	err := jpeg.Encode(&encoded, frame, nil)
	return encoded.Bytes(), err
}

func startDesktopDemo() (string, func(), error) {
	encoded, err := desktopDemoJPEG()
	if err != nil {
		return "", nil, err
	} else {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			writer := multipart.NewWriter(w)
			w.Header().Set("Content-Type", "multipart/x-mixed-replace; boundary="+writer.Boundary())
			timer := time.NewTicker(200 * time.Millisecond)
			defer timer.Stop()
			for {
				select {
				case <-r.Context().Done():
					return
				case <-timer.C:
					if err := writeDemoFrame(writer, encoded); err != nil {
						return
					} else if err := http.NewResponseController(w).Flush(); err != nil {
						return
					} else {
						continue
					}
				}
			}
		}))
		return server.URL, server.Close, nil
	}
}

func writeDemoFrame(writer *multipart.Writer, data []byte) error {
	part, err := writer.CreatePart(textproto.MIMEHeader{"Content-Type": {"image/jpeg"}})
	if err != nil {
		return err
	} else {
		_, err := part.Write(data)
		return err
	}
}

func desktopSelfTest() error {
	address, closeServer, err := startDesktopDemo()
	if err != nil {
		return err
	} else {
		defer closeServer()
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		var frame image.Image
		streamErr := readDesktopStream(ctx, address, func(next image.Image) {
			frame = next
			cancel()
		})
		if frame == nil {
			return fmt.Errorf("demo sent no MJPEG frame: %w", streamErr)
		} else {
			return verifyDesktopFrame(frame)
		}
	}
}

func verifyDesktopFrame(frame image.Image) error {
	dst := image.NewRGBA(image.Rect(0, 0, 640, 384))
	paintDesktopFrame(dst, frame, true)
	leftRed := dst.RGBAAt(80, 160)
	leftGreen := dst.RGBAAt(240, 160)
	if frame.Bounds().Size() != (image.Point{X: 320, Y: 180}) || leftRed.R < 200 || leftGreen.G < 180 ||
		dst.RGBAAt(400, 160) != leftRed || dst.RGBAAt(560, 160) != leftGreen || dst.RGBAAt(80, 60) != (color.RGBA{A: 255}) {
		return fmt.Errorf("expected matching red and green images in both eye views with black letterboxing, got %v and %v", leftRed, leftGreen)
	} else {
		return nil
	}
}
