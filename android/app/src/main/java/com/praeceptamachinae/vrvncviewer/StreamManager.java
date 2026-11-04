package com.praeceptamachinae.vrvncviewer;

import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;

class StreamManager {
    enum StreamType {
        VNC,
        MJPEG
    }

    interface StatusListener {
        void onStatus(String message);
        void onError(String message);
        void onConnected(String url);
        void onDisconnected();
    }

    private static final StreamManager INSTANCE = new StreamManager();

    static StreamManager getInstance() {
        return INSTANCE;
    }

    private FrameStore frameStore = new FrameStore();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, String> passwordCache = new HashMap<String, String>();
    private StreamSession session;
    // Connection state and activity callbacks are owned by the main thread.
    private StatusListener statusListener;
    private boolean connected;
    private String lastStatus;
    private boolean flipHorizontal = true;

    FrameStore getFrameStore() {
        return frameStore;
    }

    void setFlipHorizontal(boolean enabled) {
        flipHorizontal = enabled;
    }

    boolean isFlipHorizontal() {
        return flipHorizontal;
    }

    void cachePassword(String url, String password) {
        if (url == null || url.trim().isEmpty() || password == null || password.isEmpty()) {
            return;
        }
        passwordCache.put(url.trim(), password);
    }

    String getCachedPassword(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        return passwordCache.get(url.trim());
    }

    void setStatusListener(StatusListener listener) {
        statusListener = listener;
        String status = lastStatus;
        if (listener != null && status != null) {
            listener.onStatus(status);
        }
    }

    boolean isConnected() {
        return connected;
    }

    FrameStore connect(StreamType type, String url, String password) {
        StreamSession previous = session;
        frameStore = new FrameStore();
        connected = false;
        lastStatus = "Connecting...";
        if (statusListener != null) {
            statusListener.onStatus(lastStatus);
        }
        SessionListener listener = new SessionListener(frameStore, url);
        if (type == StreamType.MJPEG) {
            session = new MjpegStreamSession(url, frameStore, listener);
        } else {
            session = new VncStreamSession(url, password, frameStore, listener);
        }
        if (previous != null) {
            // Closing a network stream can wait for an in-progress read.
            new Thread(previous::stop, "Stream-Close").start();
        }
        session.start();
        return frameStore;
    }

    private final class SessionListener implements StreamSession.Listener {
        private final FrameStore frames;
        private final String url;

        SessionListener(FrameStore frames, String url) {
            this.frames = frames;
            this.url = url;
        }

        @Override
        public void onConnected() {
            mainHandler.post(() -> {
                if (frames == frameStore) {
                    connected = true;
                    lastStatus = "Connected";
                    if (statusListener != null) statusListener.onConnected(url);
                }
            });
        }

        @Override
        public void onError(String message, Exception e) {
            mainHandler.post(() -> {
                if (frames == frameStore) {
                    connected = false;
                    lastStatus = message;
                    frames.clear();
                    if (statusListener != null) statusListener.onError(message);
                }
            });
        }

        @Override
        public void onDisconnected() {
            mainHandler.post(() -> {
                if (frames == frameStore && connected) {
                    connected = false;
                    lastStatus = "Disconnected";
                    frames.clear();
                    if (statusListener != null) statusListener.onDisconnected();
                }
            });
        }
    }
}
