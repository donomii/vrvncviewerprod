//go:build darwin && !ios

package main

import (
	"context"
	"fmt"
	"image"
	"image/color"
	"log"
	"os"
	"strings"

	"golang.org/x/exp/shiny/driver"
	"golang.org/x/exp/shiny/screen"
	"golang.org/x/image/draw"
	"golang.org/x/image/font"
	"golang.org/x/image/font/basicfont"
	"golang.org/x/image/math/fixed"
	"golang.org/x/mobile/event/key"
	"golang.org/x/mobile/event/lifecycle"
	"golang.org/x/mobile/event/paint"
	"golang.org/x/mobile/event/size"
)

const desktopConfig = "desktop-stream.txt"

type desktopViewer struct {
	screen screen.Screen
	window screen.Window
	buffer screen.Buffer
	frame  image.Image
	status string
	split  bool
}

type desktopStreamError struct{ err error }

func main() {
	if len(os.Args) == 2 && os.Args[1] == "-test" {
		if err := desktopSelfTest(); err != nil {
			log.Fatal(err)
		} else if err := desktopVNCSelfTest(); err != nil {
			log.Fatal(err)
		} else {
			fmt.Println("PASS: MJPEG, VNC and two-eye layout")
		}
	} else {
		address, closeDemo, err := desktopAddress(os.Args[1:])
		if err != nil {
			log.Fatal(err)
		} else {
			defer closeDemo()
			driver.Main(func(s screen.Screen) {
				if err := showDesktop(s, address); err != nil {
					log.Fatal(err)
				} else {
					return
				}
			})
		}
	}
}

func desktopAddress(args []string) (string, func(), error) {
	if len(args) == 1 && args[0] == "-demo" {
		return startDesktopDemo()
	} else if len(args) == 0 {
		address, err := readDesktopAddress()
		return address, func() {}, err
	} else {
		return "", nil, fmt.Errorf("expected no arguments, -demo, or -test")
	}
}

func readDesktopAddress() (string, error) {
	data, err := os.ReadFile(desktopConfig)
	if err != nil {
		return "", fmt.Errorf("read %s: %w", desktopConfig, err)
	} else {
		address := strings.TrimSpace(string(data))
		return address, validateDesktopURL(address)
	}
}

func showDesktop(s screen.Screen, address string) error {
	window, err := s.NewWindow(&screen.NewWindowOptions{Width: 960, Height: 600, Title: "VR VNC Viewer"})
	if err != nil {
		return fmt.Errorf("open viewer window: %w", err)
	} else {
		defer window.Release()
		return runDesktopViewer(s, window, address)
	}
}

func runDesktopViewer(s screen.Screen, window screen.Window, address string) error {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	viewer := desktopViewer{screen: s, window: window, status: "Connecting to stream..."}
	defer viewer.releaseBuffer()
	frames := make(chan image.Image, 1)
	go runDesktopStream(ctx, window, address, frames)
	for {
		switch event := window.NextEvent().(type) {
		case lifecycle.Event:
			if event.To == lifecycle.StageDead {
				return nil
			} else {
				continue
			}
		case size.Event:
			if err := viewer.resize(event.Size()); err != nil {
				return err
			} else {
				viewer.paint()
			}
		case paint.Event:
			viewer.paint()
		case key.Event:
			if event.Code == key.CodeSpacebar && event.Direction == key.DirPress {
				viewer.split = !viewer.split
				viewer.paint()
			} else {
				continue
			}
		case desktopStreamError:
			viewer.frame = nil
			viewer.status = event.err.Error()
			viewer.paint()
		case desktopFrameReady:
			viewer.frame = <-frames
			viewer.status = "Space switches between one view and two eyes."
			viewer.paint()
		}
	}
}

type desktopFrameReady struct{}

func runDesktopStream(ctx context.Context, window screen.Window, address string, frames chan<- image.Image) {
	err := readDesktopStream(ctx, address, func(frame image.Image) {
		select {
		case frames <- frame:
			window.Send(desktopFrameReady{})
		default:
			// Let the display catch up before queuing another frame.
			return
		}
	})
	if ctx.Err() == nil {
		window.Send(desktopStreamError{err})
	} else {
		return
	}
}

func (viewer *desktopViewer) releaseBuffer() {
	if viewer.buffer != nil {
		viewer.buffer.Release()
		viewer.buffer = nil
	} else {
		return
	}
}

func (viewer *desktopViewer) resize(dimensions image.Point) error {
	viewer.releaseBuffer()
	if dimensions.X <= 0 || dimensions.Y <= 0 {
		return nil
	} else {
		buffer, err := viewer.screen.NewBuffer(dimensions)
		viewer.buffer = buffer
		return err
	}
}

func (viewer *desktopViewer) paint() {
	if viewer.buffer == nil {
		return
	} else {
		dst := viewer.buffer.RGBA()
		bounds := dst.Bounds()
		paintDesktopFrame(dst, viewer.frame, viewer.split)
		label := font.Drawer{Dst: dst, Src: image.NewUniform(color.White), Face: basicfont.Face7x13, Dot: fixed.P(8, bounds.Dy()-8)}
		label.DrawString(viewer.status)
		viewer.window.Upload(image.Point{}, viewer.buffer, bounds)
		viewer.window.Publish()
	}
}

func paintDesktopFrame(dst *image.RGBA, frame image.Image, split bool) {
	bounds := dst.Bounds()
	draw.Draw(dst, bounds, image.Black, image.Point{}, draw.Src)
	if frame == nil {
		return
	} else {
		content := image.Rect(0, 0, bounds.Dx(), max(0, bounds.Dy()-24))
		source := frame.Bounds()
		for _, eye := range desktopEyes(content, split) {
			draw.ApproxBiLinear.Scale(dst, fitDesktopFrame(eye, source.Size()), frame, source, draw.Src, nil)
		}
	}
}

func desktopEyes(bounds image.Rectangle, split bool) []image.Rectangle {
	if split {
		mid := bounds.Min.X + bounds.Dx()/2
		return []image.Rectangle{
			image.Rect(bounds.Min.X, bounds.Min.Y, mid, bounds.Max.Y),
			image.Rect(mid, bounds.Min.Y, bounds.Max.X, bounds.Max.Y),
		}
	} else {
		return []image.Rectangle{bounds}
	}
}

func fitDesktopFrame(eye image.Rectangle, frame image.Point) image.Rectangle {
	scale := min(float64(eye.Dx())/float64(frame.X), float64(eye.Dy())/float64(frame.Y))
	width, height := int(float64(frame.X)*scale), int(float64(frame.Y)*scale)
	x, y := eye.Min.X+(eye.Dx()-width)/2, eye.Min.Y+(eye.Dy()-height)/2
	return image.Rect(x, y, x+width, y+height)
}
