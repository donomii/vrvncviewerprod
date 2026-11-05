package com.praeceptamachinae.vrvncviewer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.Deflater;

import org.junit.Test;

public class ZrleDecoderTest {
    private static final int RED = 0xffff0000;
    private static final int GREEN = 0xff00ff00;

    @Test
    public void packedPaletteRowsAndFollowingRectangleUseOneStream() throws IOException {
        Deflater encoder = new Deflater();
        try (ZrleDecoder decoder = new ZrleDecoder()) {
            byte[] tile = {2, 0, 0, (byte) 255, 0, (byte) 255, 0, 0x40, (byte) 0xa0};
            int[] frame = new int[10];
            decoder.readRectangle(rectangle(encoder, tile), 1, 0, 3, 2, 5, frame);
            assertArrayEquals(new int[]{0, RED, GREEN, RED, 0, 0, GREEN, RED, GREEN, 0}, frame);
            int[] next = new int[6];
            decoder.readRectangle(rectangle(encoder, tile), 0, 0, 3, 2, 3, next);
            assertArrayEquals(new int[]{RED, GREEN, RED, GREEN, RED, GREEN}, next);
        } finally {
            encoder.end();
        }
    }

    @Test
    public void rejectsMalformedTileData() throws IOException {
        assertInvalid(new byte[]{127}, "subencoding");
        assertInvalid(new byte[]{(byte) 129}, "subencoding");
        assertInvalid(new byte[]{3, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xc0}, "palette index");
        assertInvalid(new byte[]{(byte) 128, 0, 0, 0, 1}, "tile remainder");
        assertInvalid(new byte[]{1, 0, 0}, "before its final tile");
        assertInvalid(new byte[]{1, 0, 0, 0, 0}, "after its final tile");
    }

    private static void assertInvalid(byte[] tile, String reason) throws IOException {
        Deflater encoder = new Deflater();
        try (ZrleDecoder decoder = new ZrleDecoder()) {
            DataInputStream in = rectangle(encoder, tile);
            IOException failure = assertThrows(IOException.class,
                    () -> decoder.readRectangle(in, 0, 0, 1, 1, 1, new int[1]));
            assertTrue(failure.getMessage(), failure.getMessage().contains(reason));
        } finally {
            encoder.end();
        }
    }

    private static DataInputStream rectangle(Deflater encoder, byte[] tile) {
        byte[] compressed = new byte[128];
        encoder.setInput(tile);
        int size = encoder.deflate(compressed, 0, compressed.length, Deflater.SYNC_FLUSH);
        assertTrue(size < compressed.length && encoder.needsInput());
        byte[] framed = ByteBuffer.allocate(4 + size).putInt(size).put(compressed, 0, size).array();
        return new DataInputStream(new ByteArrayInputStream(framed));
    }
}
