package com.praeceptamachinae.vrvncviewer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

class MjpegStreamSession implements StreamSession, Runnable {
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 5000;

    private final String url;
    private final FrameStore frameStore;
    private final Listener listener;
    private volatile boolean running;
    private Thread thread;
    private volatile HttpURLConnection connection;
    private volatile InputStream input;

    MjpegStreamSession(String rawUrl, FrameStore frameStore, Listener listener) {
        this.url = EndpointParser.normalizeMjpegUrl(rawUrl);
        this.frameStore = frameStore;
        this.listener = listener;
    }

    @Override
    public void start() {
        running = true;
        thread = new Thread(this, "MJPEG-Session");
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
        if (url == null || url.isEmpty()) {
            listener.onError("Invalid MJPEG URL", null);
            return;
        }
        try {
            URL target = new URL(url);
            connection = (HttpURLConnection) target.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.connect();
            input = new BufferedInputStream(connection.getInputStream());
            listener.onConnected();

            ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
            Bitmap reuse = null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inMutable = true;

            while (running) {
                byte[] jpeg = readJpegFrame(input, buffer);
                if (jpeg == null) {
                    break;
                }
                Bitmap decoded = decodeJpeg(jpeg, options, reuse);
                if (decoded != null) {
                    reuse = decoded;
                    frameStore.updateFrame(decoded);
                }
            }
        } catch (IOException e) {
            if (running) {
                listener.onError("MJPEG connection failed", e);
            }
        } finally {
            running = false;
            closeConnection();
            listener.onDisconnected();
        }
    }

    private static Bitmap decodeJpeg(byte[] data, BitmapFactory.Options options, Bitmap reuse) {
        try {
            options.inBitmap = reuse;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (IllegalArgumentException e) {
            options.inBitmap = null;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        }
    }

    private static byte[] readJpegFrame(InputStream input, ByteArrayOutputStream buffer) throws IOException {
        buffer.reset();
        int prev = -1;
        int cur;
        while ((cur = input.read()) != -1) {
            if (prev == 0xFF && cur == 0xD8) {
                buffer.write(0xFF);
                buffer.write(0xD8);
                break;
            }
            prev = cur;
        }
        if (cur == -1) {
            return null;
        }
        prev = -1;
        while ((cur = input.read()) != -1) {
            buffer.write(cur);
            if (prev == 0xFF && cur == 0xD9) {
                break;
            }
            prev = cur;
        }
        if (cur == -1) {
            return null;
        }
        return buffer.toByteArray();
    }

    private void closeConnection() {
        InputStream localInput = input;
        HttpURLConnection localConnection = connection;
        input = null;
        connection = null;
        if (localInput != null) {
            try {
                localInput.close();
            } catch (IOException e) {
                // ignore
            }
        }
        if (localConnection != null) {
            localConnection.disconnect();
        }
    }
}
