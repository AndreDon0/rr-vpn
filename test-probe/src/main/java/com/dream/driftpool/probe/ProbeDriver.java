package com.dream.driftpool.probe;

import android.app.Instrumentation;
import android.os.Bundle;
import java.net.HttpURLConnection;
import java.net.URL;

/** Separate UID: unlike the VPN app, these HTTPS requests must traverse the VPN interface. */
public final class ProbeDriver extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        android.net.ConnectivityManager cm = getTargetContext().getSystemService(android.net.ConnectivityManager.class);
        android.net.NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (capabilities == null || !capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
            Bundle missing = new Bundle(); missing.putString("error", "VPN is not the probe app's active network");
            finish(0, missing); return;
        }
        int successes = 0;
        int failures = 0;
        for (int i = 0; i < 8; i++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("https://www.gstatic.com/generate_204").openConnection();
                connection.setRequestProperty("Connection", "close");
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                if (connection.getResponseCode() == 204) successes++; else failures++;
            } catch (Exception e) { failures++; }
            finally { if (connection != null) connection.disconnect(); }
        }
        Bundle result = new Bundle();
        result.putInt("https_successes", successes);
        result.putInt("https_failures", failures);
        capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
        boolean vpnActive = capabilities != null && capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
        result.putBoolean("vpn_active", vpnActive);
        finish(failures == 0 && vpnActive ? -1 : 0, result);
    }
}
