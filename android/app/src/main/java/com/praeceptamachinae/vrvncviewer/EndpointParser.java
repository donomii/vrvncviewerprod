package com.praeceptamachinae.vrvncviewer;

import java.net.URI;
import java.net.URISyntaxException;

class EndpointParser {
    static class HostPort {
        final String host;
        final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    static String normalizeMjpegUrl(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "http://" + trimmed;
    }

    static HostPort parseVnc(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!trimmed.startsWith("vnc://")) {
            trimmed = "vnc://" + trimmed;
        }
        try {
            URI uri = new URI(trimmed);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || host.isEmpty()) {
                return null;
            }
            if (port <= 0) {
                port = 5900;
            }
            return new HostPort(host, port);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
