package com.baher.phonescreencast;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.IntentCompat;

import java.io.IOException;
import java.net.BindException;

/**
 * Foreground service (type mediaProjection) that owns the whole stream:
 * MediaProjection capture + WebRTC + local signaling server + network monitoring.
 */
public class ScreenCaptureService extends Service
        implements SignalingServer.Listener, WebRTCManager.Listener {

    private static final String TAG = "ScreenCaptureService";

    public static final String ACTION_START = "com.baher.phonescreencast.action.START";
    public static final String ACTION_STOP = "com.baher.phonescreencast.action.STOP";
    private static final String EXTRA_RESULT_CODE = "result_code";
    private static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "screen_streaming";
    private static final int NOTIFICATION_ID = 1001;
    private static final int PORT_FIRST = 8080;
    private static final int PORT_LAST = 8089;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Nullable private WebRTCManager rtc;
    @Nullable private volatile SignalingServer server;
    @Nullable private NetworkUtils.Monitor networkMonitor;
    @Nullable private String localIp;
    private int port = PORT_FIRST;
    private String viewerStatus = "";
    private boolean streaming;

    public static Intent createStartIntent(Context context, int resultCode, Intent projectionData) {
        return new Intent(context, ScreenCaptureService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, projectionData);
    }

    public static Intent createStopIntent(Context context) {
        return new Intent(context, ScreenCaptureService.class).setAction(ACTION_STOP);
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            shutdown(null);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Must happen promptly after startForegroundService(), and before MediaProjection is used.
        if (!enterForeground()) return START_NOT_STICKY;
        if (streaming) return START_NOT_STICKY; // duplicate start, already running

        startStreaming(intent);
        return START_NOT_STICKY;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (rtc != null) rtc.updateCaptureSize(); // rotation: resize the capture to match
    }

    @Override
    public void onDestroy() {
        cleanup();
        if (StreamRepository.current().state != StreamRepository.State.IDLE) {
            StreamRepository.publish(StreamRepository.Status.idle(null));
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------- startup

    private boolean enterForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "startForeground failed", e);
            shutdown(getString(R.string.error_service_start) + " (" + e.getMessage() + ")");
            return false;
        }
    }

    private void startStreaming(Intent intent) {
        streaming = true;
        StreamRepository.publish(StreamRepository.Status.starting());

        String ip = NetworkUtils.getLocalIpAddress(this);
        if (ip == null) {
            shutdown(getString(R.string.error_no_wifi));
            return;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent projectionData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent.class);
        if (resultCode != Activity.RESULT_OK || projectionData == null) {
            shutdown(getString(R.string.error_permission_denied));
            return;
        }

        try {
            rtc = new WebRTCManager(getApplicationContext(), projectionData, this);
            rtc.start();
        } catch (RuntimeException e) {
            Log.e(TAG, "WebRTC start failed", e);
            shutdown(e.getMessage());
            return;
        }

        try {
            server = startServer(ip);
        } catch (BindException e) {
            Log.e(TAG, "No free port", e);
            shutdown("Local server failed: ports " + PORT_FIRST + "-" + PORT_LAST + " are already in use.");
            return;
        } catch (IOException e) {
            Log.e(TAG, "Server start failed", e);
            shutdown("Local server failed: " + e.getMessage());
            return;
        }
        localIp = ip;
        viewerStatus = getString(R.string.viewer_waiting);
        publishStreaming();

        networkMonitor = new NetworkUtils.Monitor(this, this::onNetworkChanged);
        networkMonitor.start();
    }

    private SignalingServer startServer(String ip) throws IOException {
        BindException lastBind = null;
        for (int p = PORT_FIRST; p <= PORT_LAST; p++) {
            SignalingServer candidate = new SignalingServer(getAssets(), ip, p, this);
            try {
                candidate.startServer();
                port = p;
                return candidate;
            } catch (BindException e) {
                lastBind = e;
            }
        }
        throw lastBind != null ? lastBind : new BindException("No free port");
    }

    // ----------------------------------------------------- network changes

    private void onNetworkChanged() {
        if (!streaming) return;
        String ip = NetworkUtils.getLocalIpAddress(this);

        if (ip == null) {
            stopServerOnly();
            if (rtc != null) rtc.onViewerDisconnected();
            localIp = null;
            viewerStatus = "Wi-Fi disconnected. Waiting for the network…";
            publishStreaming();
            return;
        }
        if (ip.equals(localIp) && server != null) return;

        // New address (or Wi-Fi came back): rebind the server and show the new URL.
        stopServerOnly();
        if (rtc != null) rtc.onViewerDisconnected();
        try {
            server = startServer(ip);
            localIp = ip;
            viewerStatus = getString(R.string.viewer_waiting);
            publishStreaming();
        } catch (IOException e) {
            Log.e(TAG, "Server restart failed", e);
            shutdown("Local server failed after a network change: " + e.getMessage());
        }
    }

    // ------------------------------------------------ signaling -> WebRTC

    @Override
    public void onViewerConnected() {
        main.post(() -> {
            if (rtc != null) rtc.onViewerConnected();
        });
    }

    @Override
    public void onSignalMessage(@NonNull String json) {
        main.post(() -> {
            if (rtc != null) rtc.onSignal(json);
        });
    }

    @Override
    public void onViewerDisconnected() {
        main.post(() -> {
            if (rtc != null) rtc.onViewerDisconnected();
        });
    }

    // ------------------------------------------------ WebRTC -> signaling/UI

    @Override
    public void onSignalOut(@NonNull String json) {
        SignalingServer s = server;
        if (s != null) s.sendToViewer(json);
    }

    @Override
    public void onViewerState(@NonNull String text) {
        main.post(() -> {
            if (!streaming) return;
            viewerStatus = text;
            publishStreaming();
        });
    }

    @Override
    public void onFatalError(@NonNull String message) {
        shutdown(message);
    }

    // --------------------------------------------------------------- state

    private void publishStreaming() {
        String url = localIp != null ? "http://" + localIp + ":" + port : null;
        StreamRepository.publish(new StreamRepository.Status(
                StreamRepository.State.STREAMING, url, viewerStatus, null));
    }

    private void stopServerOnly() {
        SignalingServer s = server;
        server = null;
        if (s != null) s.stopServer();
    }

    private void cleanup() {
        streaming = false;
        if (networkMonitor != null) {
            networkMonitor.stop();
            networkMonitor = null;
        }
        stopServerOnly();
        if (rtc != null) {
            rtc.release();
            rtc = null;
        }
        localIp = null;
    }

    /** Stops everything. {@code error} is shown in the app; null means a normal stop. */
    private void shutdown(@Nullable String error) {
        cleanup();
        StreamRepository.publish(StreamRepository.Status.idle(error));
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, createStopIntent(this),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_cast)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(open)
                .addAction(R.drawable.ic_stat_cast, getString(R.string.notification_stop), stop)
                .build();
    }
}
