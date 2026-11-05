package com.praeceptamachinae.vrvncviewer;

import android.graphics.Bitmap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

class VncStreamSession implements StreamSession, Runnable {
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int SECURITY_NONE = 1;
    private static final int SECURITY_VNC = 2;
    private static final int MAX_REASON_BYTES = 64 * 1024;
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    private static final int DISCARD_BUFFER_BYTES = 8192;
    private static final int MAX_FRAME_SIDE = 4096;
    private static final int ENCODING_RAW = 0;
    private static final int ENCODING_COPY_RECT = 1;
    private static final int ENCODING_HEXTILE = 5;
    private static final int ENCODING_ZRLE = 16;
    private static final int ENCODING_LAST_RECT = -224;
    private static final int ENCODING_DESKTOP_SIZE = -223;
    private static final int ENCODING_CURSOR = -239;
    private static final String DEFAULT_PASSWORD = "aaaaaaaa";
    private static final int HEXTILE_SIZE = 16;
    private static final int HEXTILE_RAW = 1;
    private static final int HEXTILE_BACKGROUND = 2;
    private static final int HEXTILE_FOREGROUND = 4;
    private static final int HEXTILE_SUBRECTS = 8;
    private static final int HEXTILE_COLORED = 16;

    private final EndpointParser.HostPort endpoint;
    private final String password;
    private final FrameStore frameStore;
    private final Listener listener;
    private volatile boolean running;
    private Thread thread;
    private volatile Socket socketRef;
    private volatile DataInputStream inputRef;
    private volatile DataOutputStream outputRef;

    VncStreamSession(String rawUrl, String password, FrameStore frameStore, Listener listener) {
        this.endpoint = EndpointParser.parseVnc(rawUrl);
        if (password == null || password.trim().isEmpty()) {
            this.password = DEFAULT_PASSWORD;
        } else {
            this.password = password;
        }
        this.frameStore = frameStore;
        this.listener = listener;
    }

    @Override
    public void start() {
        running = true;
        thread = new Thread(this, "VNC-Session");
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
        closeConnection();
    }

    @Override
    public void run() {
        if (endpoint == null) {
            listener.onError("Invalid VNC address", null);
            return;
        }
        Socket socket = null;
        DataInputStream in = null;
        DataOutputStream out = null;
        try (ZrleDecoder zrle = new ZrleDecoder()) {
            socket = new Socket();
            socket.connect(new InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            socketRef = socket;
            inputRef = in;
            outputRef = out;

            authenticate(in, out);

            out.writeByte(1); // shared
            out.flush();

            int fbWidth = readU16(in);
            int fbHeight = readU16(in);
            checkFrameSize(fbWidth, fbHeight);
            PixelFormat.read(in);
            discardText(in, "desktop name");

            PixelFormat desiredFormat = PixelFormat.create32();
            sendSetPixelFormat(out, desiredFormat);
            sendSetEncodings(out, new int[]{ENCODING_COPY_RECT, ENCODING_ZRLE, ENCODING_HEXTILE,
                    ENCODING_RAW, ENCODING_DESKTOP_SIZE, ENCODING_LAST_RECT});
            out.flush();

            int[] pixels = new int[fbWidth * fbHeight];
            byte[] rowBuffer = new byte[MAX_FRAME_SIDE * (desiredFormat.bitsPerPixel / 8)];
            Bitmap bitmap = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.RGB_565);

            listener.onConnected();
            requestUpdate(out, false, fbWidth, fbHeight);
            out.flush();

            boolean hasFramebuffer = false;
            while (running) {
                int messageType;
                try {
                    messageType = in.readUnsignedByte();
                } catch (SocketTimeoutException timeout) {
                    // An incremental request may wait until the desktop changes.
                    if (hasFramebuffer) {
                        continue;
                    } else {
                        throw timeout;
                    }
                }
                if (messageType == 0) {
                    in.readUnsignedByte(); // padding
                    int rectCount = readU16(in);
                    int remainingPixels = MAX_FRAME_SIDE * MAX_FRAME_SIDE;
                    int remainingHextilePaint = 4 * remainingPixels;
                    boolean updated = false;
                    boolean resizedDesktop = false;
                    for (int i = 0; i < rectCount; i++) {
                        int rectX = readU16(in);
                        int rectY = readU16(in);
                        int rectW = readU16(in);
                        int rectH = readU16(in);
                        int encoding = in.readInt();
                        if (encoding == ENCODING_LAST_RECT) {
                            break;
                        } else if (resizedDesktop) {
                            throw new IOException("VNC DesktopSize can only be followed by LastRect in an update");
                        } else if (encoding == ENCODING_RAW || encoding == ENCODING_COPY_RECT
                                || encoding == ENCODING_HEXTILE || encoding == ENCODING_ZRLE) {
                            checkRectangle(rectX, rectY, rectW, rectH, fbWidth, fbHeight);
                            remainingPixels -= rectW * rectH;
                            if (remainingPixels < 0) {
                                throw new IOException("VNC update exceeds " + MAX_FRAME_SIDE * MAX_FRAME_SIDE + " pixels");
                            }
                            if (encoding == ENCODING_RAW) {
                                readRawRect(in, rectX, rectY, rectW, rectH, fbWidth, pixels, desiredFormat, rowBuffer);
                            } else if (encoding == ENCODING_HEXTILE) {
                                remainingHextilePaint = readHextileRect(in, rectX, rectY, rectW, rectH,
                                        fbWidth, pixels, desiredFormat, rowBuffer, remainingHextilePaint);
                            } else if (encoding == ENCODING_ZRLE) {
                                zrle.readRectangle(in, rectX, rectY, rectW, rectH, fbWidth, pixels);
                            } else {
                                if (!hasFramebuffer) {
                                    throw new IOException("VNC CopyRect needs framebuffer pixels before copying");
                                }
                                int sourceX = readU16(in);
                                int sourceY = readU16(in);
                                checkRectangle(sourceX, sourceY, rectW, rectH, fbWidth, fbHeight);
                                copyRect(pixels, fbWidth, sourceX, sourceY, rectX, rectY, rectW, rectH);
                            }
                            bitmap.setPixels(pixels, rectY * fbWidth + rectX, fbWidth,
                                    rectX, rectY, rectW, rectH);
                            hasFramebuffer = true;
                            updated = true;
                        } else if (encoding == ENCODING_DESKTOP_SIZE) {
                            checkFrameSize(rectW, rectH);
                            int[] resized = new int[rectW * rectH];
                            int sharedWidth = Math.min(fbWidth, rectW);
                            for (int row = 0; row < Math.min(fbHeight, rectH); row++) {
                                System.arraycopy(pixels, row * fbWidth, resized, row * rectW, sharedWidth);
                            }
                            fbWidth = rectW;
                            fbHeight = rectH;
                            pixels = resized;
                            bitmap = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.RGB_565);
                            bitmap.setPixels(pixels, 0, fbWidth, 0, 0, fbWidth, fbHeight);
                            // Keep the previously published image until the server supplies pixels at the new size.
                            hasFramebuffer = false;
                            updated = false;
                            resizedDesktop = true;
                        } else if (encoding == ENCODING_CURSOR) {
                            if (rectW > MAX_FRAME_SIDE || rectH > MAX_FRAME_SIDE) {
                                throw new MetadataLimitException("VNC cursor " + rectW + "×" + rectH
                                        + " exceeds the " + MAX_FRAME_SIDE + "-pixel side limit");
                            } else {
                                int bytesPerPixel = desiredFormat.bitsPerPixel / 8;
                                int pixelBytes = rectW * rectH * bytesPerPixel;
                                int maskBytes = ((rectW + 7) / 8) * rectH;
                                discard(in, pixelBytes + maskBytes);
                            }
                        } else {
                            throw new IOException("Unsupported VNC encoding: " + encoding);
                        }
                    }
                    if (updated) {
                        frameStore.updateFrame(bitmap);
                    }
                    requestUpdate(out, hasFramebuffer, fbWidth, fbHeight);
                    out.flush();
                } else if (messageType == 2) {
                    // bell
                } else if (messageType == 3) {
                    in.readUnsignedByte();
                    in.readUnsignedByte();
                    in.readUnsignedByte();
                    discardText(in, "clipboard");
                } else {
                    throw new IOException("Unsupported server message: " + messageType);
                }
            }
        } catch (Exception e) {
            if (running) {
                listener.onError(e instanceof MetadataLimitException ? e.getMessage() : "VNC connection failed", e);
            }
        } finally {
            running = false;
            closeConnection();
            listener.onDisconnected();
        }
    }

    private void closeConnection() {
        DataInputStream localInput = inputRef;
        DataOutputStream localOutput = outputRef;
        Socket localSocket = socketRef;
        inputRef = null;
        outputRef = null;
        socketRef = null;
        if (localInput != null) {
            try {
                localInput.close();
            } catch (IOException e) {
                // ignore
            }
        }
        if (localOutput != null) {
            try {
                localOutput.close();
            } catch (IOException e) {
                // ignore
            }
        }
        if (localSocket != null) {
            try {
                localSocket.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private static void requestUpdate(DataOutputStream out, boolean incremental, int w, int h) throws IOException {
        out.writeByte(3);
        out.writeByte(incremental ? 1 : 0);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(w);
        out.writeShort(h);
    }

    private static void sendSetPixelFormat(DataOutputStream out, PixelFormat format) throws IOException {
        out.writeByte(0);
        out.writeByte(0);
        out.writeByte(0);
        out.writeByte(0);
        format.write(out);
    }

    private static void sendSetEncodings(DataOutputStream out, int[] encodings) throws IOException {
        out.writeByte(2);
        out.writeByte(0);
        out.writeShort(encodings.length);
        for (int encoding : encodings) {
            out.writeInt(encoding);
        }
    }

    private void authenticate(DataInputStream in, DataOutputStream out) throws Exception {
        String version = new String(readBytes(in, 12), StandardCharsets.US_ASCII);
        String reply = negotiatedVersion(version);
        out.write(reply.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        boolean legacy = "RFB 003.003\n".equals(reply);
        boolean modern = "RFB 003.008\n".equals(reply);
        int type = selectSecurity(in, out, legacy);
        if (type == SECURITY_VNC) {
            out.write(vncEncrypt(readBytes(in, 16), password));
            out.flush();
            checkSecurityResult(in, modern);
        } else if (modern) {
            checkSecurityResult(in, true);
        }
    }

    private static String negotiatedVersion(String offered) throws IOException {
        if (!offered.matches("RFB 003\\.[0-9]{3}\n") || offered.compareTo("RFB 003.003\n") < 0) {
            throw new IOException("Unsupported server version, expected RFB 3.3 or later in the 3.x series");
        } else if ("RFB 003.007\n".equals(offered) || "RFB 003.008\n".equals(offered)) {
            return offered;
        } else {
            return "RFB 003.003\n";
        }
    }

    private static int selectSecurity(DataInputStream in, DataOutputStream out, boolean legacy) throws IOException {
        if (legacy) {
            // RFB 3.3 sends a single U32 security type and expects no selection byte.
            int type = readU32(in);
            if (type == 0) {
                throw new IOException(readReason(in));
            } else if (type == SECURITY_NONE || type == SECURITY_VNC) {
                return type;
            } else {
                throw new IOException("Unsupported VNC security type: " + type);
            }
        } else {
            int count = in.readUnsignedByte();
            if (count == 0) {
                throw new IOException(readReason(in));
            } else {
                int type = pickSecurityType(readBytes(in, count));
                out.writeByte(type);
                out.flush();
                return type;
            }
        }
    }

    private static void checkSecurityResult(DataInputStream in, boolean modern) throws IOException {
        if (readU32(in) != 0) {
            // Before RFB 3.8, a failed password challenge has no trailing reason.
            throw new IOException(modern ? readReason(in) : "VNC server rejected authentication");
        }
    }

    private static int pickSecurityType(byte[] types) throws IOException {
        boolean hasNone = false;
        boolean hasVnc = false;
        for (byte type : types) {
            if (type == SECURITY_NONE) {
                hasNone = true;
            } else if (type == SECURITY_VNC) {
                hasVnc = true;
            }
        }
        if (hasNone) {
            return SECURITY_NONE;
        } else if (hasVnc) {
            return SECURITY_VNC;
        } else {
            throw new IOException("No supported VNC security type");
        }
    }

    private static String readReason(DataInputStream in) throws IOException {
        int len = readU32(in);
        if (len > MAX_REASON_BYTES) {
            throw new IOException("VNC server error message exceeds " + MAX_REASON_BYTES + " bytes");
        } else {
            return new String(readBytes(in, len), StandardCharsets.US_ASCII);
        }
    }

    private static void checkFrameSize(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_FRAME_SIDE || height > MAX_FRAME_SIDE) {
            throw new IOException("VNC framebuffer is " + width + "×" + height
                    + ", expected dimensions from 1 through " + MAX_FRAME_SIDE);
        }
    }

    private static void checkRectangle(int x, int y, int width, int height, int frameWidth, int frameHeight) throws IOException {
        if (width == 0 || height == 0 || x + width > frameWidth || y + height > frameHeight) {
            throw new IOException("VNC rectangle " + x + "," + y + " " + width + "×" + height
                    + " is outside the " + frameWidth + "×" + frameHeight + " framebuffer");
        }
    }

    private static void copyRect(int[] pixels, int stride, int sourceX, int sourceY,
                                 int destX, int destY, int width, int height) {
        // Copy bottom-up when moving down so vertically overlapping rows survive.
        boolean movingDown = destY > sourceY;
        for (int i = 0; i < height; i++) {
            int row = movingDown ? height - 1 - i : i;
            System.arraycopy(pixels, (sourceY + row) * stride + sourceX,
                    pixels, (destY + row) * stride + destX, width);
        }
    }

    private static void readRawRect(DataInputStream in, int rectX, int rectY, int rectW, int rectH,
                                    int fbWidth, int[] pixels, PixelFormat format, byte[] data) throws IOException {
        int bytesPerPixel = format.bitsPerPixel / 8;
        for (int y = 0; y < rectH; y++) {
            in.readFully(data, 0, rectW * bytesPerPixel);
            int rowStart = (rectY + y) * fbWidth + rectX;
            int offset = 0;
            for (int x = 0; x < rectW; x++) {
                int raw = readPixel(data, offset, bytesPerPixel, format.bigEndian);
                pixels[rowStart + x] = toArgb(raw, format);
                offset += bytesPerPixel;
            }
        }
    }

    private static int readHextileRect(DataInputStream in, int x, int y, int width, int height,
                                       int stride, int[] pixels, PixelFormat format, byte[] buffer,
                                       int remainingPaint) throws IOException {
        // Zero marks an unspecified color. Decoded colors always have opaque alpha.
        int background = 0;
        int foreground = 0;
        for (int tileY = y; tileY < y + height; tileY += HEXTILE_SIZE) {
            for (int tileX = x; tileX < x + width; tileX += HEXTILE_SIZE) {
                int tileWidth = Math.min(HEXTILE_SIZE, x + width - tileX);
                int tileHeight = Math.min(HEXTILE_SIZE, y + height - tileY);
                int flags = in.readUnsignedByte();
                if ((flags & HEXTILE_RAW) != 0) {
                    readRawRect(in, tileX, tileY, tileWidth, tileHeight, stride, pixels, format, buffer);
                    background = 0;
                    foreground = 0;
                    continue;
                }
                boolean colored = (flags & HEXTILE_COLORED) != 0;
                boolean hasForeground = (flags & HEXTILE_FOREGROUND) != 0;
                if ((flags & ~31) != 0 || (colored && hasForeground)) {
                    throw new IOException("Invalid VNC Hextile flags " + flags);
                }
                if ((flags & HEXTILE_BACKGROUND) != 0) background = readColor(in, format, buffer);
                if (background == 0) throw new IOException("VNC Hextile tile has no background color");
                if (colored) {
                    foreground = 0;
                } else if (hasForeground) {
                    foreground = readColor(in, format, buffer);
                }
                fillPixels(pixels, stride, tileX, tileY, tileWidth, tileHeight, background);
                int count = (flags & HEXTILE_SUBRECTS) != 0 ? in.readUnsignedByte() : 0;
                if (count > 0 && !colored && foreground == 0) {
                    throw new IOException("VNC Hextile subrectangles have no foreground color");
                }
                for (int i = 0; i < count; i++) {
                    int color = colored ? readColor(in, format, buffer) : foreground;
                    int position = in.readUnsignedByte();
                    int size = in.readUnsignedByte();
                    int subX = position >> 4;
                    int subY = position & 15;
                    int subWidth = (size >> 4) + 1;
                    int subHeight = (size & 15) + 1;
                    checkRectangle(subX, subY, subWidth, subHeight, tileWidth, tileHeight);
                    remainingPaint -= subWidth * subHeight;
                    if (remainingPaint < 0) throw new IOException("VNC Hextile update exceeds its paint limit");
                    fillPixels(pixels, stride, tileX + subX, tileY + subY, subWidth, subHeight, color);
                }
            }
        }
        return remainingPaint;
    }

    private static void fillPixels(int[] pixels, int stride, int x, int y, int width, int height, int color) {
        for (int row = y; row < y + height; row++) {
            int start = row * stride + x;
            Arrays.fill(pixels, start, start + width, color);
        }
    }

    private static int readColor(DataInputStream in, PixelFormat format, byte[] buffer) throws IOException {
        int size = format.bitsPerPixel / 8;
        in.readFully(buffer, 0, size);
        return toArgb(readPixel(buffer, 0, size, format.bigEndian), format);
    }

    private static int toArgb(int raw, PixelFormat format) {
        int r = scaleColor((raw >> format.redShift) & format.redMax, format.redMax);
        int g = scaleColor((raw >> format.greenShift) & format.greenMax, format.greenMax);
        int b = scaleColor((raw >> format.blueShift) & format.blueMax, format.blueMax);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int scaleColor(int value, int max) {
        if (max == 0) {
            return 0;
        }
        if (max == 255) {
            return value;
        }
        return (value * 255) / max;
    }

    private static int readPixel(byte[] data, int offset, int bytesPerPixel, int bigEndian) {
        int raw = 0;
        if (bytesPerPixel == 4) {
            if (bigEndian != 0) {
                raw = ((data[offset] & 0xFF) << 24) |
                        ((data[offset + 1] & 0xFF) << 16) |
                        ((data[offset + 2] & 0xFF) << 8) |
                        (data[offset + 3] & 0xFF);
            } else {
                raw = (data[offset] & 0xFF) |
                        ((data[offset + 1] & 0xFF) << 8) |
                        ((data[offset + 2] & 0xFF) << 16) |
                        ((data[offset + 3] & 0xFF) << 24);
            }
        } else if (bytesPerPixel == 3) {
            if (bigEndian != 0) {
                raw = ((data[offset] & 0xFF) << 16) |
                        ((data[offset + 1] & 0xFF) << 8) |
                        (data[offset + 2] & 0xFF);
            } else {
                raw = (data[offset] & 0xFF) |
                        ((data[offset + 1] & 0xFF) << 8) |
                        ((data[offset + 2] & 0xFF) << 16);
            }
        } else if (bytesPerPixel == 2) {
            if (bigEndian != 0) {
                raw = ((data[offset] & 0xFF) << 8) |
                        (data[offset + 1] & 0xFF);
            } else {
                raw = (data[offset] & 0xFF) |
                        ((data[offset + 1] & 0xFF) << 8);
            }
        } else if (bytesPerPixel == 1) {
            raw = data[offset] & 0xFF;
        }
        return raw;
    }

    private static int readU16(DataInputStream in) throws IOException {
        return in.readUnsignedShort();
    }

    private static int readU32(DataInputStream in) throws IOException {
        long value = in.readInt() & 0xFFFFFFFFL;
        if (value > Integer.MAX_VALUE) {
            throw new IOException("Value too large");
        }
        return (int) value;
    }

    private static void discardText(DataInputStream in, String field) throws IOException {
        long length = in.readInt() & 0xFFFFFFFFL;
        if (length > MAX_TEXT_BYTES) {
            throw new MetadataLimitException("VNC " + field + " length " + length
                    + " exceeds " + MAX_TEXT_BYTES + " bytes");
        } else {
            discard(in, (int) length);
        }
    }

    private static void discard(DataInputStream in, int length) throws IOException {
        byte[] buffer = new byte[Math.min(length, DISCARD_BUFFER_BYTES)];
        while (length > 0) {
            int chunk = Math.min(length, buffer.length);
            in.readFully(buffer, 0, chunk);
            length -= chunk;
        }
    }

    private static class MetadataLimitException extends IOException {
        MetadataLimitException(String message) {
            super(message);
        }
    }

    private static byte[] readBytes(DataInputStream in, int len) throws IOException {
        if (len <= 0) {
            return new byte[0];
        }
        byte[] data = new byte[len];
        in.readFully(data);
        return data;
    }

    private static byte[] vncEncrypt(byte[] challenge, String password) throws Exception {
        byte[] key = new byte[8];
        byte[] pass = password.getBytes(StandardCharsets.ISO_8859_1);
        int copyLen = Math.min(pass.length, key.length);
        System.arraycopy(pass, 0, key, 0, copyLen);
        for (int i = 0; i < key.length; i++) {
            key[i] = reverseBits(key[i]);
        }
        SecretKeySpec keySpec = new SecretKeySpec(key, "DES");
        Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        return cipher.doFinal(challenge);
    }

    private static byte reverseBits(byte value) {
        int v = value & 0xFF;
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | (v & 1);
            v >>= 1;
        }
        return (byte) r;
    }

    private static class PixelFormat {
        int bitsPerPixel;
        int depth;
        int bigEndian;
        int trueColor;
        int redMax;
        int greenMax;
        int blueMax;
        int redShift;
        int greenShift;
        int blueShift;

        static PixelFormat read(DataInputStream in) throws IOException {
            PixelFormat f = new PixelFormat();
            f.bitsPerPixel = in.readUnsignedByte();
            f.depth = in.readUnsignedByte();
            f.bigEndian = in.readUnsignedByte();
            f.trueColor = in.readUnsignedByte();
            f.redMax = in.readUnsignedShort();
            f.greenMax = in.readUnsignedShort();
            f.blueMax = in.readUnsignedShort();
            f.redShift = in.readUnsignedByte();
            f.greenShift = in.readUnsignedByte();
            f.blueShift = in.readUnsignedByte();
            in.readUnsignedByte();
            in.readUnsignedByte();
            in.readUnsignedByte();
            return f;
        }

        void write(DataOutputStream out) throws IOException {
            out.writeByte(bitsPerPixel);
            out.writeByte(depth);
            out.writeByte(bigEndian);
            out.writeByte(trueColor);
            out.writeShort(redMax);
            out.writeShort(greenMax);
            out.writeShort(blueMax);
            out.writeByte(redShift);
            out.writeByte(greenShift);
            out.writeByte(blueShift);
            out.writeByte(0);
            out.writeByte(0);
            out.writeByte(0);
        }

        static PixelFormat create32() {
            PixelFormat f = new PixelFormat();
            f.bitsPerPixel = 32;
            f.depth = 24;
            f.bigEndian = 0;
            f.trueColor = 1;
            f.redMax = 255;
            f.greenMax = 255;
            f.blueMax = 255;
            f.redShift = 16;
            f.greenShift = 8;
            f.blueShift = 0;
            return f;
        }
    }
}
