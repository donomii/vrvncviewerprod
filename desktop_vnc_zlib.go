//go:build darwin && !ios

package main

/*
#cgo LDFLAGS: -lz
#include <stdlib.h>
#include <zlib.h>

static z_stream *zrle_open(void) {
	z_stream *stream = calloc(1, sizeof(*stream));
	if (stream != NULL && inflateInit(stream) != Z_OK) {
		free(stream);
		return NULL;
	} else {
		return stream;
	}
}

static void zrle_close(z_stream *stream) {
	inflateEnd(stream);
	free(stream);
}

static int zrle_inflate(z_stream *stream, Bytef *input, uInt *input_size, Bytef *output, uInt *output_size) {
	stream->next_in = input;
	stream->avail_in = *input_size;
	stream->next_out = output;
	stream->avail_out = *output_size;
	int status = inflate(stream, Z_NO_FLUSH);
	*input_size -= stream->avail_in;
	*output_size -= stream->avail_out;
	// The caller owns these buffers. Retain only zlib's internal state.
	stream->next_in = Z_NULL;
	stream->next_out = Z_NULL;
	stream->avail_in = 0;
	stream->avail_out = 0;
	return status;
}
*/
import "C"

import (
	"fmt"
	"io"
)

type vncZlib struct {
	stream    *C.z_stream
	source    io.Reader
	remaining int
	chunk     [8192]byte
	input     []byte
	finished  bool
}

func (reader *vncZlib) Read(output []byte) (int, error) {
	if len(output) == 0 {
		return 0, nil
	} else if reader.finished {
		return 0, io.EOF
	}
	for {
		if err := reader.fillInput(); err != nil {
			return 0, err
		}
		var input *C.uchar
		if len(reader.input) != 0 {
			input = (*C.uchar)(&reader.input[0])
		} else {
			input = nil
		}
		consumed, produced := C.uint(len(reader.input)), C.uint(len(output))
		status := C.zrle_inflate(reader.stream, input, &consumed, (*C.uchar)(&output[0]), &produced)
		reader.input = reader.input[int(consumed):]
		switch {
		case status == C.Z_STREAM_END:
			reader.finished = true
			if reader.remaining != 0 || len(reader.input) != 0 {
				return 0, fmt.Errorf("compressed bytes after the ZRLE stream ended")
			} else {
				return int(produced), io.EOF
			}
		case status != C.Z_OK && status != C.Z_BUF_ERROR:
			return 0, fmt.Errorf("inflate ZRLE data: %s", C.GoString(C.zError(status)))
		case produced != 0:
			return int(produced), nil
		case consumed != 0:
			continue
		case reader.remaining == 0 && len(reader.input) == 0:
			return 0, io.EOF
		default:
			return 0, fmt.Errorf("ZRLE inflater made no progress")
		}
	}
}

func (reader *vncZlib) fillInput() error {
	if len(reader.input) != 0 || reader.remaining == 0 {
		return nil
	} else {
		chunk := reader.chunk[:min(len(reader.chunk), reader.remaining)]
		if _, err := io.ReadFull(reader.source, chunk); err != nil {
			return fmt.Errorf("read declared ZRLE compressed bytes: %w", err)
		} else {
			reader.input = chunk
			reader.remaining -= len(chunk)
			return nil
		}
	}
}

func openVNCZlib(source io.Reader) (*vncZlib, error) {
	if stream := C.zrle_open(); stream == nil {
		return nil, fmt.Errorf("initialize ZRLE zlib stream")
	} else {
		return &vncZlib{stream: stream, source: source}, nil
	}
}

func (reader *vncZlib) close() {
	C.zrle_close(reader.stream)
}
