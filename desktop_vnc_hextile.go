//go:build darwin && !ios

package main

import (
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"io"
)

const (
	vncHextile        = 5
	hextileSide       = 16
	hextilePaintLimit = 4 * maxFrameSide * maxFrameSide
)

const (
	hextileRaw byte = 1 << iota
	hextileBackground
	hextileForeground
	hextileSubrects
	hextileColored
)

type hextileColors struct {
	background color.RGBA
	foreground color.RGBA
}

func (reader *vncReader) readHextileRect(rect image.Rectangle, paint *int) error {
	var colors hextileColors
	for y := rect.Min.Y; y < rect.Max.Y; y += hextileSide {
		for x := rect.Min.X; x < rect.Max.X; x += hextileSide {
			tile := image.Rect(x, y, min(x+hextileSide, rect.Max.X), min(y+hextileSide, rect.Max.Y))
			if err := reader.readHextileTile(tile, &colors, paint); err != nil {
				return fmt.Errorf("VNC Hextile tile %v: %w", tile, err)
			}
		}
	}
	return nil
}

func (reader *vncReader) readHextileTile(tile image.Rectangle, colors *hextileColors, paint *int) error {
	flags, err := reader.input.ReadByte()
	colored := flags&hextileColored != 0
	if err != nil {
		return err
	} else if flags&hextileRaw != 0 {
		*colors = hextileColors{}
		return reader.readRawRect(tile)
	} else if flags&^(hextileRaw|hextileBackground|hextileForeground|hextileSubrects|hextileColored) != 0 ||
		(colored && flags&hextileForeground != 0) {
		return fmt.Errorf("invalid tile flags %#x", flags)
	}
	if flags&hextileBackground != 0 {
		colors.background, err = reader.readHextileColor()
		if err != nil {
			return err
		}
	} else if colors.background.A == 0 {
		return fmt.Errorf("tile has no background color")
	}
	if colored {
		colors.foreground = color.RGBA{}
	} else if flags&hextileForeground != 0 {
		colors.foreground, err = reader.readHextileColor()
		if err != nil {
			return err
		}
	}
	draw.Draw(reader.frame, tile, image.NewUniform(colors.background), image.Point{}, draw.Src)
	if flags&hextileSubrects != 0 {
		return reader.readHextileSubrects(tile, colors.foreground, colored, paint)
	} else {
		return nil
	}
}

func (reader *vncReader) readHextileSubrects(tile image.Rectangle, foreground color.RGBA, colored bool, paint *int) error {
	count, err := reader.input.ReadByte()
	if err != nil {
		return err
	} else if count != 0 && !colored && foreground.A == 0 {
		return fmt.Errorf("subrectangles have no foreground color")
	}
	for i := 0; i < int(count); i++ {
		pixel := foreground
		if colored {
			pixel, err = reader.readHextileColor()
			if err != nil {
				return err
			}
		}
		var geometry [2]byte
		if _, err := io.ReadFull(reader.input, geometry[:]); err != nil {
			return err
		}
		x, y := tile.Min.X+int(geometry[0]>>4), tile.Min.Y+int(geometry[0]&15)
		width, height := int(geometry[1]>>4)+1, int(geometry[1]&15)+1
		rect := image.Rect(x, y, x+width, y+height)
		if !rect.In(tile) {
			return fmt.Errorf("subrectangle %v is outside tile %v", rect, tile)
		} else if *paint < width*height {
			return fmt.Errorf("subrectangle paint exceeds %d pixels per update", hextilePaintLimit)
		} else {
			*paint -= width * height
			draw.Draw(reader.frame, rect, image.NewUniform(pixel), image.Point{}, draw.Src)
		}
	}
	return nil
}

func (reader *vncReader) readHextileColor() (color.RGBA, error) {
	var pixel [4]byte
	_, err := io.ReadFull(reader.input, pixel[:])
	return color.RGBA{R: pixel[2], G: pixel[1], B: pixel[0], A: 255}, err
}
