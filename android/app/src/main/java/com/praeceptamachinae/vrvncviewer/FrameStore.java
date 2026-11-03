package com.praeceptamachinae.vrvncviewer;

import android.graphics.Bitmap;

class FrameStore {
    static class Snapshot {
        Bitmap frame;
        int width;
        int height;
        int version;
    }

    private final Object lock = new Object();
    private Bitmap frame;
    private int width;
    private int height;
    private int version;

    void updateFrame(Bitmap newFrame) {
        if (newFrame == null) {
            return;
        }
        synchronized (lock) {
            frame = newFrame;
            width = newFrame.getWidth();
            height = newFrame.getHeight();
            version++;
        }
    }

    void clear() {
        synchronized (lock) {
            frame = null;
            width = 0;
            height = 0;
            version++;
        }
    }

    void readSnapshot(Snapshot out) {
        synchronized (lock) {
            out.frame = frame;
            out.width = width;
            out.height = height;
            out.version = version;
        }
    }
}
