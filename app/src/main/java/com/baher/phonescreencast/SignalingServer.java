package com.baher.phonescreencast;

import android.content.res.AssetManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

/**
 * Local HTTP + WebSocket server on a single port.
 *
 * <ul>
 *   <li>GET /, /style.css, /viewer.js: the browser viewer, served from assets/viewer</li>
 *   <li>WebSocket /ws: WebRTC signaling (offer / answer / candidate JSON messages)</li>
 * </ul>
 *
 * Only one viewer is active at a time (a new viewer replaces the old one), which keeps CPU use
 * low on weak hardware. The server is bound to the Wi-Fi address only, never to mobile data.
 */
public class SignalingServer extends NanoWSD {

    private static final String TAG = "SignalingServer";
    private static final int SOCKET_TIMEOUT_MS = 30_000; // the viewer pings every 5 s
    private static final int MAX_MESSAGE_CHARS = 64 * 1024;

    public interface Listener {
        /** A viewer opened the signaling socket (called on a server thread). */
        void onViewerConnected();

        /** A signaling message arrived from the active viewer (called on a server thread). */
        void onSignalMessage(@NonNull String json);

        /** The active viewer went away (called on a server thread). */
        void onViewerDisconnected();
    }

    /** Hook for optional authentication in a future version. Default: allow everyone on the LAN. */
    public interface AccessPolicy {
        boolean allow(@NonNull IHTTPSession session);
    }

    private final AssetManager assets;
    private final Listener listener;
    private final Object lock = new Object();
    private volatile AccessPolicy accessPolicy = session -> true;
    @Nullable private ViewerSocket active;

    public SignalingServer(@NonNull AssetManager assets, @NonNull String host, int port,
                           @NonNull Listener listener) {
        super(host, port);
        this.assets = assets;
        this.listener = listener;
    }

    public void setAccessPolicy(@NonNull AccessPolicy policy) {
        this.accessPolicy = policy;
    }

    public void startServer() throws IOException {
        start(SOCKET_TIMEOUT_MS, false);
    }

    public void stopServer() {
        ViewerSocket old;
        synchronized (lock) {
            old = active;
            active = null; // so the close callback does not notify the listener
        }
        if (old != null) closeQuietly(old, WebSocketFrame.CloseCode.GoingAway, "Server stopped");
        stop();
    }

    /** Sends a JSON message to the active viewer, if any. */
    public void sendToViewer(@NonNull String json) {
        ViewerSocket s;
        synchronized (lock) {
            s = active;
        }
        if (s == null) return;
        try {
            s.send(json);
        } catch (IOException e) {
            Log.w(TAG, "Send failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ HTTP

    @Override
    public Response serve(IHTTPSession session) {
        if (isWebSocketUpgrade(session)) {
            if (!"/ws".equals(session.getUri())) {
                return text(Response.Status.NOT_FOUND, "Not found");
            }
            // Block cross-site WebSocket hijacking: a random web page must not be able to
            // open this socket and receive the screen stream.
            if (!isSameOrigin(session) || !accessPolicy.allow(session)) {
                return text(Response.Status.FORBIDDEN, "Forbidden");
            }
            return super.serve(session);
        }

        Method method = session.getMethod();
        if (method != Method.GET && method != Method.HEAD) {
            return text(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed");
        }
        if (!accessPolicy.allow(session)) {
            return text(Response.Status.FORBIDDEN, "Forbidden");
        }
        return serveAsset(session.getUri());
    }

    private Response serveAsset(String uri) {
        String path;
        String mime;
        switch (uri) {
            case "/":
            case "/index.html":
                path = "viewer/index.html";
                mime = "text/html; charset=utf-8";
                break;
            case "/style.css":
                path = "viewer/style.css";
                mime = "text/css; charset=utf-8";
                break;
            case "/viewer.js":
                path = "viewer/viewer.js";
                mime = "application/javascript; charset=utf-8";
                break;
            case "/favicon.ico":
                return text(Response.Status.NO_CONTENT, "");
            default:
                return text(Response.Status.NOT_FOUND, "Not found");
        }
        try {
            InputStream in = assets.open(path);
            return secure(newChunkedResponse(Response.Status.OK, mime, in));
        } catch (IOException e) {
            Log.e(TAG, "Missing asset " + path, e);
            return text(Response.Status.INTERNAL_ERROR, "Viewer files are missing");
        }
    }

    private static Response text(Response.IStatus status, String body) {
        return secure(newFixedLengthResponse(status, MIME_PLAINTEXT, body));
    }

    private static Response secure(Response r) {
        r.addHeader("Cache-Control", "no-store");
        r.addHeader("X-Content-Type-Options", "nosniff");
        r.addHeader("Referrer-Policy", "no-referrer");
        return r;
    }

    private static boolean isWebSocketUpgrade(IHTTPSession session) {
        String upgrade = session.getHeaders().get("upgrade");
        return upgrade != null && "websocket".equalsIgnoreCase(upgrade);
    }

    private static boolean isSameOrigin(IHTTPSession session) {
        Map<String, String> headers = session.getHeaders();
        String origin = headers.get("origin");
        String host = headers.get("host");
        if (origin == null) return true; // non-browser client
        if (host == null) return false;
        return origin.equalsIgnoreCase("http://" + host);
    }

    // ------------------------------------------------------------- WebSocket

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        return new ViewerSocket(handshake);
    }

    private void onViewerOpened(ViewerSocket socket) {
        ViewerSocket old;
        synchronized (lock) {
            old = active;
            active = socket;
        }
        if (old != null) {
            closeQuietly(old, WebSocketFrame.CloseCode.GoingAway, "Replaced by another viewer");
        }
        listener.onViewerConnected();
    }

    private void onViewerClosed(ViewerSocket socket) {
        boolean wasActive;
        synchronized (lock) {
            wasActive = (active == socket);
            if (wasActive) active = null;
        }
        if (wasActive) listener.onViewerDisconnected();
    }

    private void onViewerMessage(ViewerSocket socket, @Nullable String text) {
        if (text == null || text.length() > MAX_MESSAGE_CHARS) return;
        synchronized (lock) {
            if (active != socket) return;
        }
        // Keep-alive pings only reset the socket timeout.
        if (text.length() < 40 && text.contains("\"ping\"")) return;
        listener.onSignalMessage(text);
    }

    private static void closeQuietly(ViewerSocket socket, WebSocketFrame.CloseCode code, String reason) {
        try {
            socket.close(code, reason, false);
        } catch (IOException ignored) {
            // already closed
        }
    }

    private final class ViewerSocket extends WebSocket {
        ViewerSocket(IHTTPSession handshake) {
            super(handshake);
        }

        @Override
        protected void onOpen() {
            onViewerOpened(this);
        }

        @Override
        protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
            onViewerClosed(this);
        }

        @Override
        protected void onMessage(WebSocketFrame message) {
            onViewerMessage(this, message.getTextPayload());
        }

        @Override
        protected void onPong(WebSocketFrame pong) {
            // not used
        }

        @Override
        protected void onException(IOException exception) {
            Log.d(TAG, "Socket exception: " + exception.getMessage());
        }
    }
}
