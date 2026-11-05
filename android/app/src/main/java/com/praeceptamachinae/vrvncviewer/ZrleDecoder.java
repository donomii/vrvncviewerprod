package com.praeceptamachinae.vrvncviewer;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Decodes ZRLE for the viewer's requested 32-bit little-endian BGR pixel format. */
final class ZrleDecoder implements AutoCloseable {
    private static final int TILE_SIZE = 64;
    private static final int BUFFER_BYTES = 8192;
    private final Inflater inflater = new Inflater();
    private final byte[] compressed = new byte[BUFFER_BYTES];
    private final byte[] decoded = new byte[BUFFER_BYTES];
    private final int[] tile = new int[TILE_SIZE * TILE_SIZE];
    private final int[] palette = new int[127];
    private int remaining;
    private int position;
    private int available;

    void readRectangle(DataInputStream in, int x, int y, int width, int height,
                       int stride, int[] pixels) throws IOException {
        long length = in.readInt() & 0xffffffffL;
        long tiles = (long) tileCount(width) * tileCount(height);
        // Allow worst-case run and palette data, plus zlib framing overhead.
        long limit = 64 + 5L * width * height + 512 * tiles;
        if (length == 0 || length > limit || inflater.finished()) {
            throw new IOException("Invalid ZRLE rectangle length or stream state: " + length);
        } else {
            remaining = (int) length;
        }
        for (int top = 0; top < height; top += TILE_SIZE) {
            int rows = Math.min(TILE_SIZE, height - top);
            for (int left = 0; left < width; left += TILE_SIZE) {
                int cols = Math.min(TILE_SIZE, width - left);
                readTile(in, cols, rows);
                for (int row = 0; row < rows; row++) {
                    System.arraycopy(tile, row * cols, pixels, (y + top + row) * stride + x + left, cols);
                }
            }
        }
        if (position == available && !refill(in)) {
            return;
        } else {
            throw new IOException("ZRLE rectangle contains data after its final tile");
        }
    }

    private static int tileCount(int side) {
        return (side + TILE_SIZE - 1) / TILE_SIZE;
    }

    private void readTile(DataInputStream in, int width, int height) throws IOException {
        int mode = readByte(in);
        int size = width * height;
        if (mode == 0) {
            for (int i = 0; i < size; i++) tile[i] = readColor(in);
        } else if (mode == 1) {
            Arrays.fill(tile, 0, size, readColor(in));
        } else if (mode <= 16) {
            readPalette(in, mode);
            readPackedTile(in, width, height, mode);
        } else if (mode == 128) {
            readRunTile(in, size, 0);
        } else if (mode >= 130) {
            int colors = mode - 128;
            readPalette(in, colors);
            readRunTile(in, size, colors);
        } else {
            throw new IOException("Invalid ZRLE tile subencoding: " + mode);
        }
    }

    private void readPalette(DataInputStream in, int size) throws IOException {
        for (int i = 0; i < size; i++) palette[i] = readColor(in);
    }

    private void readPackedTile(DataInputStream in, int width, int height, int colors) throws IOException {
        int bits = colors == 2 ? 1 : colors <= 4 ? 2 : 4;
        int mask = (1 << bits) - 1;
        for (int row = 0; row < height; row++) {
            int packed = 0;
            for (int col = 0; col < width; col++) {
                int shift = 8 - bits - (col * bits) % 8;
                packed = shift == 8 - bits ? readByte(in) : packed;
                tile[row * width + col] = paletteColor((packed >> shift) & mask, colors);
            }
        }
    }

    private void readRunTile(DataInputStream in, int size, int colors) throws IOException {
        int offset = 0;
        while (offset < size) {
            int color;
            int length;
            if (colors == 0) {
                color = readColor(in);
                length = readRunLength(in, size - offset);
            } else {
                int index = readByte(in);
                color = paletteColor(index & 127, colors);
                length = (index & 128) != 0 ? readRunLength(in, size - offset) : 1;
            }
            Arrays.fill(tile, offset, offset + length, color);
            offset += length;
        }
    }

    private int paletteColor(int index, int colors) throws IOException {
        if (index >= colors) {
            throw new IOException("ZRLE palette index " + index + " exceeds " + colors + " colors");
        } else {
            return palette[index];
        }
    }

    private int readRunLength(DataInputStream in, int capacity) throws IOException {
        int length = 1;
        int next;
        do {
            next = readByte(in);
            length += next;
            if (length > capacity) {
                throw new IOException("ZRLE run of " + length + " pixels exceeds tile remainder " + capacity);
            } else {
                continue;
            }
        } while (next == 255);
        return length;
    }

    private int readColor(DataInputStream in) throws IOException {
        int blue = readByte(in);
        int green = readByte(in);
        int red = readByte(in);
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    private int readByte(DataInputStream in) throws IOException {
        if (position == available && !refill(in)) {
            throw new IOException("ZRLE rectangle ended before its final tile");
        } else {
            return decoded[position++] & 255;
        }
    }

    private boolean refill(DataInputStream in) throws IOException {
        position = 0;
        available = 0;
        while (!inflater.finished()) {
            long before = inflater.getBytesRead();
            available = inflater.needsInput() && remaining > 0 ? inflateChunk(in) : inflate();
            if (available != 0) {
                return true;
            } else if (inflater.needsDictionary()) {
                throw new IOException("ZRLE stream requested an external dictionary");
            } else if (inflater.needsInput() && remaining == 0) {
                return false;
            } else if (inflater.needsInput() || inflater.finished() || inflater.getBytesRead() != before) {
                continue;
            } else {
                throw new IOException("ZRLE inflater made no progress");
            }
        }
        if (remaining != 0 || inflater.getRemaining() != 0) {
            throw new IOException("Unexpected compressed data after the ZRLE stream ended");
        } else {
            return false;
        }
    }

    private int inflateChunk(DataInputStream in) throws IOException {
        int count = Math.min(compressed.length, remaining);
        in.readFully(compressed, 0, count);
        inflater.setInput(compressed, 0, count);
        remaining -= count;
        return inflate();
    }

    private int inflate() throws IOException {
        try {
            return inflater.inflate(decoded);
        } catch (DataFormatException failure) {
            throw new IOException("Invalid ZRLE compressed data", failure);
        }
    }

    @Override
    public void close() {
        inflater.end();
    }
}
