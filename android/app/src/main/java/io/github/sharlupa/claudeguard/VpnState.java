package io.github.sharlupa.claudeguard;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.provider.Settings;

/** Определение активного VPN и открытие VPN-приложения. */
final class VpnState {
    private VpnState() {}

    @SuppressWarnings("deprecation")
    static boolean isVpnActive(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        if (isVpn(cm, cm.getActiveNetwork())) return true;
        for (Network n : cm.getAllNetworks()) {
            if (isVpn(cm, n)) return true;
        }
        return false;
    }

    private static boolean isVpn(ConnectivityManager cm, Network n) {
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    /** Запрос, под который попадают все сети, включая VPN. */
    static NetworkRequest allNetworksRequest() {
        return new NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
    }

    /** Открывает выбранное VPN-приложение, иначе системные настройки VPN. */
    static void openVpn(Context ctx) {
        String pkg = Prefs.vpnApp(ctx);
        if (pkg != null) {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
        }
        try {
            ctx.startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            ctx.startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
