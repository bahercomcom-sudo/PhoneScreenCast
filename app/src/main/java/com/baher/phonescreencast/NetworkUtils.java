package com.baher.phonescreencast;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

/** Local network helpers: finds the phone's Wi-Fi (or hotspot) IPv4 address and watches for changes. */
public final class NetworkUtils {

    private static final String TAG = "NetworkUtils";

    private NetworkUtils() {}

    /** @return the local IPv4 address on Wi-Fi (or hotspot), or null if there is none. */
    @Nullable
    public static String getLocalIpAddress(Context context) {
        String ip = getWifiIp(context);
        if (ip != null) return ip;
        return getInterfaceIp();
    }

    public static boolean isNetworkAvailable(Context context) {
        return getLocalIpAddress(context) != null;
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static String getWifiIp(Context context) {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        try {
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                LinkProperties lp = cm.getLinkProperties(network);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Wi-Fi lookup failed", e);
        }
        return null;
    }

    /** Fallback for phones acting as a hotspot (no Wi-Fi client network, but an ap/wlan interface). */
    @Nullable
    private static String getInterfaceIp() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return null;
            for (NetworkInterface ni : Collections.list(en)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase(Locale.US);
                if (!(name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
                        || name.startsWith("softap") || name.startsWith("eth"))) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Interface lookup failed", e);
        }
        return null;
    }

    /** Debounced Wi-Fi change monitor. The callback runs on the main thread. */
    public static final class Monitor {
        private final ConnectivityManager cm;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Runnable onChange;
        private final Runnable dispatch;
        private boolean registered;

        private final ConnectivityManager.NetworkCallback callback =
                new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) { notifyChanged(); }
                    @Override public void onLost(Network network) { notifyChanged(); }
                    @Override public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                        notifyChanged();
                    }
                };

        public Monitor(Context context, Runnable onChange) {
            this.cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            this.onChange = onChange;
            this.dispatch = this::runCallback;
        }

        private void runCallback() {
            onChange.run();
        }

        private void notifyChanged() {
            main.removeCallbacks(dispatch);
            main.postDelayed(dispatch, 300);
        }

        public void start() {
            if (registered || cm == null) return;
            try {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build();
                cm.registerNetworkCallback(request, callback);
                registered = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not register network callback", e);
            }
        }

        public void stop() {
            main.removeCallbacks(dispatch);
            if (!registered || cm == null) return;
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not unregister network callback", e);
            }
            registered = false;
        }
    }
}
