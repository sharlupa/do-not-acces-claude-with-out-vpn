package io.github.sharlupa.claudeguard;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Process;
import android.provider.Settings;

/** Определение активного VPN и открытие VPN-приложения. */
final class VpnState {
    private VpnState() {}

    /** Включён ли ваш VPN (собственный VPN-блокировщик не считается). */
    static boolean isVpnActive(Context ctx) {
        return findVpn(ctx, false);
    }

    /** Поднят ли VPN-блокировщик этого приложения. */
    static boolean isBlockerActive(Context ctx) {
        return findVpn(ctx, true);
    }

    @SuppressWarnings("deprecation")
    private static boolean findVpn(Context ctx, boolean ours) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            // владелец VPN-сети виден только самому владельцу, для чужих VPN тут INVALID_UID
            boolean isOurs = caps.getOwnerUid() == Process.myUid();
            if (isOurs == ours) return true;
        }
        return false;
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
