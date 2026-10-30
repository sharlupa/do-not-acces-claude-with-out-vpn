package io.github.sharlupa.claudeguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/** Запускает VPN-блокировщик после перезагрузки и после обновления приложения. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (Prefs.netBlock(context) && VpnService.prepare(context) == null) {
            BlockVpnService.start(context);
        }
    }
}
