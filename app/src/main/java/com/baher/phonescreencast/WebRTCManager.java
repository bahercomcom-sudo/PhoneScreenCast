package com.baher.phonescreencast;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsReport;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the whole WebRTC pipeline:
 * MediaProjection -> ScreenCapturerAndroid -> VideoSource -> VideoTrack -> PeerConnection.
 *
 * Threading: every public method must be called on the main thread. WebRTC callbacks arrive on
 * WebRTC's own threads and are bounced to the main thread before touching state.
 *
 * Audio extension point: {@link #addMediaTracks(PeerConnection)} is the single place where tracks
 * are attached. System-audio capture (AudioPlaybackCapture, API 29+) can be added there later by
 * creating an AudioSource/AudioTrack from a custom JavaAudioDeviceModule input.
 */
public class WebRTCManager {

    private static final String TAG = "WebRTCManager";

    public interface Listener {
        /** Outgoing signaling JSON for the viewer (may be called on a WebRTC thread). */
        void onSignalOut(@NonNull String json);

        /** Human-readable viewer connection status (may be called on any thread). */
        void onViewerState(@NonNull String text);

        /** The pipeline died (e.g. the user stopped sharing). Main thread. */
        void onFatalError(@NonNull String message);
    }

    // ---- video settings tuned for low-end hardware ----
    private static final int FPS = 30;
    private static final int MAX_LONG_SIDE = 1280;
    private static final int MAX_SHORT_SIDE = 720;
    private static final float[] QUALITY_SCALE = {1.0f, 0.75f, 0.5f};
    private static final int MIN_BITRATE_BPS = 300_000;
    private static final int START_BITRATE_BPS = 1_500_000;
    private static final int MAX_BITRATE_BPS = 2_500_000;
    private static final long QUALITY_CHECK_MS = 3_000;
    /** Codec preference in the offer: hardware H.264 (only offered when HW supports it), then VP8. */
    private static final String[] PREFERRED_CODECS = {"H264", "VP8"};

    private static boolean factoryInitialized;

    private final Context appContext;
    private final Intent projectionData;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable qualityTask = this::checkQuality;

    @Nullable private EglBase eglBase;
    @Nullable private JavaAudioDeviceModule audioDeviceModule;
    @Nullable private PeerConnectionFactory factory;
    @Nullable private SurfaceTextureHelper textureHelper;
    @Nullable private ScreenCapturerAndroid capturer;
    @Nullable private VideoSource videoSource;
    @Nullable private VideoTrack videoTrack;

    @Nullable private Session session;
    private int qualityLevel;
    private int limitedCount;
    private int lastWidth;
    private int lastHeight;
    private boolean released;

    private static final class Session {
        volatile boolean closed;
        volatile PeerConnection pc;
        boolean remoteSet;
        final List<IceCandidate> pendingCandidates = new ArrayList<>();
    }

    public WebRTCManager(@NonNull Context context, @NonNull Intent projectionData,
                         @NonNull Listener listener) {
        this.appContext = context.getApplicationContext();
        this.projectionData = projectionData;
        this.listener = listener;
    }

    // ------------------------------------------------------------ lifecycle

    /** Creates the WebRTC factory and starts capturing the screen. */
    public void start() {
        try {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(appContext)
                                .createInitializationOptions());
                factoryInitialized = true;
            }
            eglBase = EglBase.create();
            audioDeviceModule = JavaAudioDeviceModule.builder(appContext).createAudioDeviceModule();
            VideoEncoderFactory encoderFactory =
                    new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
            VideoDecoderFactory decoderFactory =
                    new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());
            factory = PeerConnectionFactory.builder()
                    .setAudioDeviceModule(audioDeviceModule)
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .createPeerConnectionFactory();
        } catch (Throwable t) {
            release();
            throw new IllegalStateException("WebRTC initialization failed: " + describe(t), t);
        }

        try {
            textureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.getEglBaseContext());
            videoSource = factory.createVideoSource(true /* screencast */);
            capturer = new ScreenCapturerAndroid(projectionData, new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    main.post(() -> {
                        if (!released) listener.onFatalError("Screen capture was stopped.");
                    });
                }
            });
            capturer.initialize(textureHelper, appContext, videoSource.getCapturerObserver());
            int[] size = computeCaptureSize();
            lastWidth = size[0];
            lastHeight = size[1];
            capturer.startCapture(size[0], size[1], FPS);
            videoTrack = factory.createVideoTrack("screen0", videoSource);
            videoTrack.setEnabled(true);
        } catch (Throwable t) {
            release();
            throw new IllegalStateException("Screen capture failed: " + describe(t), t);
        }
    }

    /** Stops capture and frees every native resource. Safe to call more than once. */
    public void release() {
        if (released) return;
        released = true;
        main.removeCallbacks(qualityTask);
        closeSession();

        if (capturer != null) {
            try {
                capturer.stopCapture();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                Log.w(TAG, "stopCapture failed", t);
            }
            try {
                capturer.dispose();
            } catch (Throwable t) {
                Log.w(TAG, "capturer dispose failed", t);
            }
            capturer = null;
        }
        if (videoTrack != null) {
            try { videoTrack.dispose(); } catch (Throwable t) { Log.w(TAG, "track dispose", t); }
            videoTrack = null;
        }
        if (videoSource != null) {
            try { videoSource.dispose(); } catch (Throwable t) { Log.w(TAG, "source dispose", t); }
            videoSource = null;
        }
        if (textureHelper != null) {
            try { textureHelper.dispose(); } catch (Throwable t) { Log.w(TAG, "helper dispose", t); }
            textureHelper = null;
        }
        if (factory != null) {
            try { factory.dispose(); } catch (Throwable t) { Log.w(TAG, "factory dispose", t); }
            factory = null;
        }
        if (audioDeviceModule != null) {
            try { audioDeviceModule.release(); } catch (Throwable t) { Log.w(TAG, "adm release", t); }
            audioDeviceModule = null;
        }
        if (eglBase != null) {
            try { eglBase.release(); } catch (Throwable t) { Log.w(TAG, "egl release", t); }
            eglBase = null;
        }
    }

    // ------------------------------------------------------- viewer events

    /** A viewer connected: build a fresh PeerConnection and send an offer. */
    public void onViewerConnected() {
        if (released || factory == null || videoTrack == null) return;
        closeSession();
        listener.onViewerState("Viewer connecting…");

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(new ArrayList<>());
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        config.disableIpv6 = true; // LAN only, host candidates are enough; no STUN/TURN needed

        final Session s = new Session();
        PeerConnection pc = factory.createPeerConnection(config, new PcObserver(s));
        if (pc == null) {
            listener.onViewerState("Could not create the WebRTC connection");
            return;
        }
        s.pc = pc;
        session = s;

        try {
            addMediaTracks(pc);
        } catch (RuntimeException e) {
            Log.e(TAG, "addMediaTracks failed", e);
            closeSession();
            listener.onViewerState("WebRTC error: " + describe(e));
            return;
        }

        pc.createOffer(new SdpAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription offer) {
                main.post(() -> {
                    if (s.closed) return;
                    applyLocalOffer(s, offer, true);
                });
            }

            @Override
            public void onCreateFailure(String error) {
                main.post(() -> failSession(s, "Could not create offer: " + error));
            }
        }, new MediaConstraints());

        scheduleQualityCheck();
    }

    public void onViewerDisconnected() {
        closeSession();
        if (!released) listener.onViewerState("Waiting for a viewer to connect");
    }

    /** Signaling message from the viewer. */
    public void onSignal(@NonNull String json) {
        if (released) return;
        try {
            JSONObject msg = new JSONObject(json);
            String type = msg.optString("type");
            switch (type) {
                case "answer":
                    handleAnswer(msg.getString("sdp"));
                    break;
                case "candidate":
                    handleCandidate(msg);
                    break;
                case "restart":
                    onViewerConnected();
                    break;
                default:
                    break;
            }
        } catch (JSONException e) {
            Log.w(TAG, "Bad signaling message: " + e.getMessage());
        }
    }

    /** Call after rotation / display changes so the captured size follows the screen. */
    public void updateCaptureSize() {
        if (released || capturer == null) return;
        int[] size = computeCaptureSize();
        if (size[0] == lastWidth && size[1] == lastHeight) return;
        lastWidth = size[0];
        lastHeight = size[1];
        try {
            capturer.changeCaptureFormat(size[0], size[1], FPS);
        } catch (RuntimeException e) {
            Log.w(TAG, "changeCaptureFormat failed", e);
        }
    }

    // ----------------------------------------------------------- internals

    /** Single place where media is attached to a connection (audio can be added here later). */
    private void addMediaTracks(PeerConnection pc) {
        RtpTransceiver.RtpTransceiverInit init = new RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                Collections.singletonList("phonescreencast"));
        pc.addTransceiver(videoTrack, init);
    }

    private void applyLocalOffer(Session s, SessionDescription original, boolean munge) {
        final SessionDescription toApply = munge
                ? new SessionDescription(original.type, preferVideoCodecs(original.description))
                : original;
        PeerConnection pc = s.pc;
        if (pc == null || s.closed) return;
        pc.setLocalDescription(new SdpAdapter() {
            @Override
            public void onSetSuccess() {
                if (s.closed) return;
                listener.onSignalOut(sdpJson("offer", toApply.description));
            }

            @Override
            public void onSetFailure(String error) {
                main.post(() -> {
                    if (s.closed) return;
                    if (munge) {
                        Log.w(TAG, "Munged offer rejected (" + error + "), retrying unmodified");
                        applyLocalOffer(s, original, false);
                    } else {
                        failSession(s, "Could not set local description: " + error);
                    }
                });
            }
        }, toApply);
    }

    private void handleAnswer(String sdp) {
        final Session s = session;
        if (s == null || s.closed || s.pc == null || s.remoteSet) return;
        s.pc.setRemoteDescription(new SdpAdapter() {
            @Override
            public void onSetSuccess() {
                main.post(() -> {
                    if (s.closed || s != session) return;
                    s.remoteSet = true;
                    for (IceCandidate c : s.pendingCandidates) s.pc.addIceCandidate(c);
                    s.pendingCandidates.clear();
                    s.pc.setBitrate(MIN_BITRATE_BPS, START_BITRATE_BPS, MAX_BITRATE_BPS);
                });
            }

            @Override
            public void onSetFailure(String error) {
                main.post(() -> failSession(s, "Could not apply the viewer's answer: " + error));
            }
        }, new SessionDescription(SessionDescription.Type.ANSWER, sdp));
    }

    private void handleCandidate(JSONObject msg) {
        final Session s = session;
        if (s == null || s.closed || s.pc == null) return;
        String sdp = msg.optString("candidate", "");
        if (sdp.isEmpty()) return; // end-of-candidates marker
        // The phone cannot resolve browser mDNS (.local) host candidates. The browser reaches the
        // phone through its own connectivity checks, so skipping them is safe.
        if (sdp.contains(".local")) return;
        IceCandidate candidate = new IceCandidate(
                msg.optString("sdpMid", "0"), msg.optInt("sdpMLineIndex", 0), sdp);
        if (s.remoteSet) {
            s.pc.addIceCandidate(candidate);
        } else {
            s.pendingCandidates.add(candidate);
        }
    }

    private void failSession(Session s, String message) {
        Log.w(TAG, message);
        if (s != session) return;
        closeSession();
        listener.onViewerState(message);
    }

    private void closeSession() {
        Session s = session;
        session = null;
        if (s == null) return;
        s.closed = true;
        main.removeCallbacks(qualityTask);
        PeerConnection pc = s.pc;
        if (pc != null) {
            try {
                pc.close();
                pc.dispose();
            } catch (Throwable t) {
                Log.w(TAG, "peer connection close failed", t);
            }
        }
    }

    // --------------------------------------------- automatic quality control

    private void scheduleQualityCheck() {
        main.removeCallbacks(qualityTask);
        main.postDelayed(qualityTask, QUALITY_CHECK_MS);
    }

    private void checkQuality() {
        final Session s = session;
        if (released || s == null || s.closed || s.pc == null) return;
        s.pc.getStats(report -> main.post(() -> evaluateQuality(s, report)));
        main.postDelayed(qualityTask, QUALITY_CHECK_MS);
    }

    /** Steps the capture resolution down when WebRTC reports CPU or bandwidth limitation. */
    private void evaluateQuality(Session s, RTCStatsReport report) {
        if (released || s != session || s.closed) return;
        for (RTCStats stats : report.getStatsMap().values()) {
            if (!"outbound-rtp".equals(stats.getType())) continue;
            Map<String, Object> members = stats.getMembers();
            Object kind = members.get("kind");
            if (kind == null) kind = members.get("mediaType");
            if (!"video".equals(kind)) continue;

            Object reason = members.get("qualityLimitationReason");
            boolean limited = "cpu".equals(reason) || "bandwidth".equals(reason);
            limitedCount = limited ? limitedCount + 1 : 0;
            if (limitedCount >= 2 && qualityLevel < QUALITY_SCALE.length - 1) {
                qualityLevel++;
                limitedCount = 0;
                Log.i(TAG, "Reducing capture quality to level " + qualityLevel + " (" + reason + ")");
                updateCaptureSize();
            }
        }
    }

    /** Fits the real screen into 1280x720 (either orientation) and applies the quality level. */
    private int[] computeCaptureSize() {
        WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        Rect bounds = wm.getMaximumWindowMetrics().getBounds();
        int w = Math.max(1, bounds.width());
        int h = Math.max(1, bounds.height());
        float fit = Math.min(1f, Math.min(
                MAX_LONG_SIDE / (float) Math.max(w, h),
                MAX_SHORT_SIDE / (float) Math.min(w, h)));
        float scale = fit * QUALITY_SCALE[qualityLevel];
        return new int[]{even(w * scale), even(h * scale)};
    }

    private static int even(float v) {
        return Math.max(2, Math.round(v)) & ~1;
    }

    // ------------------------------------------------------------- helpers

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return m != null ? m : t.getClass().getSimpleName();
    }

    private static String sdpJson(String type, String sdp) {
        try {
            return new JSONObject().put("type", type).put("sdp", sdp).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String candidateJson(IceCandidate c) {
        try {
            return new JSONObject()
                    .put("type", "candidate")
                    .put("candidate", c.sdp)
                    .put("sdpMid", c.sdpMid)
                    .put("sdpMLineIndex", c.sdpMLineIndex)
                    .toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Moves the preferred video codecs (and their RTX partners) to the front of the m=video line.
     * Software H.264 is not bundled with libwebrtc, so H.264 only appears when the device has a
     * hardware encoder; otherwise VP8 goes first, the lightest software option. Returns the input
     * unchanged if anything looks unexpected.
     */
    static String preferVideoCodecs(String sdp) {
        try {
            String[] lines = sdp.split("\r\n", -1);
            int mIndex = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith("m=video")) {
                    mIndex = i;
                    break;
                }
            }
            if (mIndex < 0) return sdp;
            String[] parts = lines[mIndex].split(" ");
            if (parts.length <= 3) return sdp;
            List<String> payloads = new ArrayList<>(Arrays.asList(parts).subList(3, parts.length));

            Pattern rtpmap = Pattern.compile("^a=rtpmap:(\\d+) ([^/]+)/.*$");
            Pattern apt = Pattern.compile("^a=fmtp:(\\d+) .*apt=(\\d+).*$");
            Map<String, String> codecOf = new HashMap<>();
            Map<String, String> rtxOf = new HashMap<>();
            for (String line : lines) {
                Matcher m = rtpmap.matcher(line);
                if (m.matches()) codecOf.put(m.group(1), m.group(2).toUpperCase(Locale.US));
                Matcher a = apt.matcher(line);
                if (a.matches()) rtxOf.put(a.group(2), a.group(1));
            }

            List<String> ordered = new ArrayList<>();
            Set<String> used = new HashSet<>();
            for (String wanted : PREFERRED_CODECS) {
                for (String pt : payloads) {
                    if (wanted.equals(codecOf.get(pt)) && used.add(pt)) {
                        ordered.add(pt);
                        String rtx = rtxOf.get(pt);
                        if (rtx != null && payloads.contains(rtx) && used.add(rtx)) ordered.add(rtx);
                    }
                }
            }
            for (String pt : payloads) {
                if (used.add(pt)) ordered.add(pt);
            }
            lines[mIndex] = parts[0] + " " + parts[1] + " " + parts[2] + " " + String.join(" ", ordered);
            return String.join("\r\n", lines);
        } catch (RuntimeException e) {
            return sdp;
        }
    }

    // ------------------------------------------------------------ observers

    private abstract static class SdpAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }

    private final class PcObserver implements PeerConnection.Observer {
        private final Session s;

        PcObserver(Session s) {
            this.s = s;
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            if (s.closed) return;
            listener.onSignalOut(candidateJson(candidate));
        }

        public void onConnectionChange(PeerConnection.PeerConnectionState state) {
            if (s.closed) return;
            Log.i(TAG, "Connection state: " + state);
            switch (state) {
                case CONNECTING:
                    listener.onViewerState("Viewer connecting…");
                    break;
                case CONNECTED:
                    listener.onViewerState("Viewer connected");
                    break;
                case DISCONNECTED:
                    listener.onViewerState("Viewer disconnected, trying to recover…");
                    break;
                case FAILED:
                    main.post(() -> failSession(s, "Connection to the viewer failed. Waiting for it to reconnect."));
                    break;
                default:
                    break;
            }
        }

        @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            Log.d(TAG, "ICE state: " + state);
        }
        @Override public void onIceConnectionReceivingChange(boolean receiving) {}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        @Override public void onAddStream(MediaStream stream) {}
        @Override public void onRemoveStream(MediaStream stream) {}
        @Override public void onDataChannel(DataChannel dataChannel) {}
        @Override public void onRenegotiationNeeded() {}
        public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {}
        public void onTrack(RtpTransceiver transceiver) {}
    }
}
