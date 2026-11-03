package com.praeceptamachinae.vrvncviewer;

import android.graphics.Bitmap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

class VncStreamSession implements StreamSession, Runnable {
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int ENCODING_RAW = 0;
    private static final int ENCODING_DESKTOP_SIZE = -223;
    private static final int ENCODING_CURSOR = -239;
    private static final String DEFAULT_PASSWORD = "aaaaaaaa";

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
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            socketRef = socket;
            inputRef = in;
            outputRef = out;

            byte[] serverVersion = readBytes(in, 12);
            if (serverVersion == null) {
                throw new IOException("Missing server version");
            }
            out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();

            int secCount = in.readUnsignedByte();
            if (secCount == 0) {
                String reason = readReason(in);
                throw new IOException(reason);
            }
            byte[] secTypes = readBytes(in, secCount);
            int secType = pickSecurityType(secTypes);
            if (secType == -1) {
                throw new IOException("No supported VNC security type");
            }
            out.writeByte(secType);
            out.flush();

            if (secType == 2) {
                byte[] challenge = readBytes(in, 16);
                if (challenge == null || challenge.length != 16) {
                    throw new IOException("Invalid VNC challenge");
                }
                byte[] response = vncEncrypt(challenge, password);
                out.write(response);
                out.flush();
            }

            int secResult = readU32(in);
            if (secResult != 0) {
                String reason = readReason(in);
                throw new IOException(reason);
            }

            out.writeByte(1); // shared
            out.flush();

            int fbWidth = readU16(in);
            int fbHeight = readU16(in);
            PixelFormat.read(in);
            int nameLen = readU32(in);
            readBytes(in, nameLen);

            PixelFormat desiredFormat = PixelFormat.create32();
            sendSetPixelFormat(out, desiredFormat);
            sendSetEncodings(out, new int[]{ENCODING_RAW});
            out.flush();

            int[] pixels = new int[fbWidth * fbHeight];
            Bitmap bitmap = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.RGB_565);

            listener.onConnected();
            requestUpdate(out, false, fbWidth, fbHeight);
            out.flush();

            while (running) {
                int messageType = in.readUnsignedByte();
                if (messageType == 0) {
                    in.readUnsignedByte(); // padding
                    int rectCount = readU16(in);
                    for (int i = 0; i < rectCount; i++) {
                        int rectX = readU16(in);
                        int rectY = readU16(in);
                        int rectW = readU16(in);
                        int rectH = readU16(in);
                        int encoding = in.readInt();
                        if (encoding == ENCODING_RAW) {
                            int bytesPerPixel = desiredFormat.bitsPerPixel / 8;
                            int dataLen = rectW * rectH * bytesPerPixel;
                            byte[] rectData = readBytes(in, dataLen);
                            if (rectData == null) {
                                throw new IOException("Missing framebuffer data");
                            }
                            applyRawRect(rectData, rectX, rectY, rectW, rectH, fbWidth, pixels, desiredFormat);
                            bitmap.setPixels(pixels, rectY * fbWidth + rectX, fbWidth,
                                    rectX, rectY, rectW, rectH);
                        } else if (encoding == ENCODING_DESKTOP_SIZE) {
                            fbWidth = rectW;
                            fbHeight = rectH;
                            pixels = new int[fbWidth * fbHeight];
                            bitmap = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.RGB_565);
                        } else if (encoding == ENCODING_CURSOR) {
                            int bytesPerPixel = desiredFormat.bitsPerPixel / 8;
                            int pixelBytes = rectW * rectH * bytesPerPixel;
                            int maskBytes = ((rectW + 7) / 8) * rectH;
                            readBytes(in, pixelBytes + maskBytes);
                        } else {
                            skipEncoding(in, encoding, rectW, rectH, desiredFormat);
                        }
                    }
                    frameStore.updateFrame(bitmap);
                    requestUpdate(out, true, fbWidth, fbHeight);
                    out.flush();
                } else if (messageType == 2) {
                    // bell
                } else if (messageType == 3) {
                    in.readUnsignedByte();
                    in.readUnsignedByte();
                    in.readUnsignedByte();
                    int textLen = readU32(in);
                    readBytes(in, textLen);
                } else {
                    throw new IOException("Unsupported server message: " + messageType);
                }
            }
        } catch (Exception e) {
            if (running) {
                listener.onError("VNC connection failed", e);
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

    private static int pickSecurityType(byte[] types) {
        boolean hasNone = false;
        boolean hasVnc = false;
        for (byte type : types) {
            if (type == 1) {
                hasNone = true;
            } else if (type == 2) {
                hasVnc = true;
            }
        }
        if (hasNone) {
            return 1;
        }
        if (hasVnc) {
            return 2;
        }
        return -1;
    }

    private static String readReason(DataInputStream in) throws IOException {
        int len = readU32(in);
        byte[] data = readBytes(in, len);
        if (data == null) {
            return "Unknown error";
        }
        return new String(data, StandardCharsets.US_ASCII);
    }

    private static void skipEncoding(DataInputStream in, int encoding, int w, int h, PixelFormat format) throws IOException {
        if (encoding == ENCODING_RAW) {
            int bytesPerPixel = format.bitsPerPixel / 8;
            int dataLen = w * h * bytesPerPixel;
            readBytes(in, dataLen);
            return;
        }
        throw new IOException("Unsupported encoding: " + encoding);
    }

    private static void applyRawRect(byte[] data, int rectX, int rectY, int rectW, int rectH,
                                     int fbWidth, int[] pixels, PixelFormat format) {
        int bytesPerPixel = format.bitsPerPixel / 8;
        int offset = 0;
        for (int y = 0; y < rectH; y++) {
            int rowStart = (rectY + y) * fbWidth + rectX;
            for (int x = 0; x < rectW; x++) {
                int raw = readPixel(data, offset, bytesPerPixel, format.bigEndian);
                int r = scaleColor((raw >> format.redShift) & format.redMax, format.redMax);
                int g = scaleColor((raw >> format.greenShift) & format.greenMax, format.greenMax);
                int b = scaleColor((raw >> format.blueShift) & format.blueMax, format.blueMax);
                pixels[rowStart + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                offset += bytesPerPixel;
            }
        }
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
