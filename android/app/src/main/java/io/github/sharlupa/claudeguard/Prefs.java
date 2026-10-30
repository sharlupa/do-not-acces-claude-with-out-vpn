package io.github.sharlupa.claudeguard;

import android.content.Context;
import android.content.SharedPreferences;

/** Настройки приложения. */
final class Prefs {
    private static final String FILE = "guard";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BROWSERS = "browsers";
    private static final String KEY_VPN_APP = "vpn_app";

    private Prefs() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context ctx, boolean value) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, value).apply();
    }

    static boolean blockBrowsers(Context ctx) {
        return prefs(ctx).getBoolean(KEY_BROWSERS, true);
    }

    static void setBlockBrowsers(Context ctx, boolean value) {
        prefs(ctx).edit().putBoolean(KEY_BROWSERS, value).apply();
    }

    /** Пакет VPN-приложения, которое открывается кнопкой «Включить VPN», или null. */
    static String vpnApp(Context ctx) {
        return prefs(ctx).getString(KEY_VPN_APP, null);
    }

    static void setVpnApp(Context ctx, String pkg) {
        prefs(ctx).edit().putString(KEY_VPN_APP, pkg).apply();
    }
}
