package io.github.sharlupa.claudeguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Запускает VPN-блокировщик после перезагрузки и после обновления приложения. */
public class BootReceiver extends BroadcastReceiver {
    /** Даём VPN-клиенту с автозапуском подключиться первым, чтобы не перебить его. */
    private static final long BOOT_DELAY_MS = 15_000;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (Prefs.netBlock(context) && Prefs.vpnConsent(context)) {
            BlockVpnService.start(context,
                    Intent.ACTION_BOOT_COMPLETED.equals(action) ? BOOT_DELAY_MS : 0);
        }
    }
}
