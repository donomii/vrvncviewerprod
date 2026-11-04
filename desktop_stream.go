//go:build darwin && !ios

package main

import (
	"bytes"
	"context"
	"fmt"
	"image"
	"image/jpeg"
	"io"
	"mime"
	"mime/multipart"
	"net"
	"net/http"
	"net/url"
	"time"
)

const (
	frameTimeout = 8 * time.Second
	maxJPEGBytes = 8 << 20
	maxFrameSide = 4096
)

func validateDesktopURL(address string) error {
	parsed, err := url.Parse(address)
	if err != nil || parsed.Hostname() == "" || parsed.User != nil {
		return fmt.Errorf("enter an HTTP(S) MJPEG or vnc:// address in desktop-stream.txt")
	} else if parsed.Scheme == "vnc" && (parsed.Path == "" || parsed.Path == "/") && parsed.RawQuery == "" && parsed.Fragment == "" {
		return nil
	} else if parsed.Scheme == "http" || parsed.Scheme == "https" {
		return nil
	} else {
		return fmt.Errorf("enter an HTTP(S) MJPEG URL or vnc://host[:port] in desktop-stream.txt")
	}
}

func readDesktopStream(ctx context.Context, address string, publish func(image.Image)) error {
	if err := validateDesktopURL(address); err != nil {
		return err
	} else if parsed, _ := url.Parse(address); parsed.Scheme == "vnc" {
		port := parsed.Port()
		if port == "" {
			return readDesktopVNC(ctx, net.JoinHostPort(parsed.Hostname(), "5900"), publish)
		} else {
			return readDesktopVNC(ctx, net.JoinHostPort(parsed.Hostname(), port), publish)
		}
	} else {
		return readDesktopMJPEG(ctx, address, publish)
	}
}

func readDesktopMJPEG(ctx context.Context, address string, publish func(image.Image)) error {
	ctx, cancel := context.WithCancelCause(ctx)
	defer cancel(nil)
	watchdog := time.AfterFunc(frameTimeout, func() {
		cancel(fmt.Errorf("MJPEG stream produced no complete frame for %s", frameTimeout))
	})
	defer watchdog.Stop()
	err := fetchDesktopStream(ctx, address, func(frame image.Image) {
		watchdog.Reset(frameTimeout)
		publish(frame)
	})
	if cause := context.Cause(ctx); cause != nil {
		return cause
	} else {
		return err
	}
}

func fetchDesktopStream(ctx context.Context, address string, publish func(image.Image)) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil {
		return fmt.Errorf("prepare MJPEG request: %w", err)
	} else {
		client := http.Client{CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		}}
		response, err := client.Do(request)
		if err != nil {
			return fmt.Errorf("open MJPEG stream: %w", err)
		} else {
			defer response.Body.Close()
			if response.StatusCode != http.StatusOK {
				return fmt.Errorf("open MJPEG stream: HTTP %d, expected 200", response.StatusCode)
			} else {
				return decodeDesktopStream(response.Body, response.Header.Get("Content-Type"), publish)
			}
		}
	}
}

func decodeDesktopStream(body io.Reader, contentType string, publish func(image.Image)) error {
	mediaType, params, err := mime.ParseMediaType(contentType)
	if err != nil || mediaType != "multipart/x-mixed-replace" || params["boundary"] == "" {
		return fmt.Errorf("expected multipart MJPEG with a boundary, received %q", contentType)
	} else {
		return readDesktopFrames(multipart.NewReader(body, params["boundary"]), publish)
	}
}

func readDesktopFrames(parts *multipart.Reader, publish func(image.Image)) error {
	for {
		part, err := parts.NextPart()
		if err == io.EOF {
			return fmt.Errorf("MJPEG stream ended")
		} else if err != nil {
			return fmt.Errorf("read MJPEG frame boundary: %w", err)
		} else {
			frame, err := readDesktopJPEG(part)
			if err != nil {
				return fmt.Errorf("read MJPEG frame: %w", err)
			} else {
				publish(frame)
			}
		}
	}
}

func readDesktopJPEG(part io.Reader) (image.Image, error) {
	data, err := io.ReadAll(io.LimitReader(part, maxJPEGBytes+1))
	if err != nil {
		return nil, err
	} else if len(data) > maxJPEGBytes {
		return nil, fmt.Errorf("JPEG exceeds %d bytes", maxJPEGBytes)
	} else {
		config, err := jpeg.DecodeConfig(bytes.NewReader(data))
		if err != nil {
			return nil, err
		} else if config.Width > maxFrameSide || config.Height > maxFrameSide {
			return nil, fmt.Errorf("JPEG dimensions %dx%d exceed %d pixels per side", config.Width, config.Height, maxFrameSide)
		} else {
			return jpeg.Decode(bytes.NewReader(data))
		}
	}
}
