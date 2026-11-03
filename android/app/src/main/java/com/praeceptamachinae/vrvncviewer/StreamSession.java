package com.praeceptamachinae.vrvncviewer;

interface StreamSession {
    interface Listener {
        void onConnected();
        void onError(String message, Exception e);
        void onDisconnected();
    }

    void start();
    void stop();
}
