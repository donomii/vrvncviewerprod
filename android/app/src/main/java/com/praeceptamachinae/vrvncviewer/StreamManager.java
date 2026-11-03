package com.praeceptamachinae.vrvncviewer;

import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;

class StreamManager implements StreamSession.Listener {
    enum StreamType {
        VNC,
        MJPEG
    }

    interface StatusListener {
        void onStatus(String message);
        void onError(String message);
        void onConnected();
        void onDisconnected();
    }

    private static final StreamManager INSTANCE = new StreamManager();

    static StreamManager getInstance() {
        return INSTANCE;
    }

    private final FrameStore frameStore = new FrameStore();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, String> passwordCache = new HashMap<String, String>();
    private StreamSession session;
    private volatile StatusListener statusListener;
    private volatile boolean connected;
    private volatile String lastStatus;
    private boolean errorState;
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

    void connect(StreamType type, String url, String password) {
        disconnect();
        frameStore.clear();
        connected = false;
        errorState = false;
        notifyStatus("Connecting...");

        StreamSession next;
        if (type == StreamType.MJPEG) {
            next = new MjpegStreamSession(url, frameStore, this);
        } else {
            next = new VncStreamSession(url, password, frameStore, this);
        }
        session = next;
        session.start();
    }

    void disconnect() {
        if (session != null) {
            session.stop();
            session = null;
        }
        connected = false;
        notifyDisconnected();
    }

    private void notifyStatus(final String message) {
        lastStatus = message;
        if (statusListener == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (statusListener != null) {
                    statusListener.onStatus(message);
                }
            }
        });
    }

    private void notifyError(final String message) {
        lastStatus = message;
        if (statusListener == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (statusListener != null) {
                    statusListener.onError(message);
                }
            }
        });
    }

    private void notifyConnected() {
        lastStatus = "Connected";
        if (statusListener == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (statusListener != null) {
                    statusListener.onConnected();
                }
            }
        });
    }

    private void notifyDisconnected() {
        lastStatus = "Disconnected";
        if (statusListener == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (statusListener != null) {
                    statusListener.onDisconnected();
                }
            }
        });
    }

    @Override
    public void onConnected() {
        connected = true;
        errorState = false;
        notifyConnected();
    }

    @Override
    public void onError(String message, Exception e) {
        connected = false;
        errorState = true;
        notifyError(message);
    }

    @Override
    public void onDisconnected() {
        connected = false;
        if (!errorState) {
            notifyDisconnected();
        }
    }
}
