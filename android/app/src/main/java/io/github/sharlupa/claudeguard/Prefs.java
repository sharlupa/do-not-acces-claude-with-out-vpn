package io.github.sharlupa.claudeguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Настройки приложения. */
final class Prefs {
    private static final String FILE = "guard";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BROWSERS = "browsers";
    private static final String KEY_VPN_APP = "vpn_app";
    private static final String KEY_NET_BLOCK = "net_block";
    private static final String KEY_ALL_APPS = "net_block_all_apps";
    private static final String KEY_VPN_CONSENT = "vpn_consent";
    private static final String KEY_CHECK_COUNTRY = "check_country";
    private static final String KEY_CACHED_IPS = "cached_ips";
    private static final String KEY_CACHED_AT = "cached_ips_at";

    private Prefs() {}

    static SharedPreferences prefs(Context ctx) {
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

    /** Сетевая блокировка через VPN-блокировщик. */
    static boolean netBlock(Context ctx) {
        return prefs(ctx).getBoolean(KEY_NET_BLOCK, false);
    }

    static void setNetBlock(Context ctx, boolean value) {
        prefs(ctx).edit().putBoolean(KEY_NET_BLOCK, value).apply();
    }

    /** Проверять страну подключения перед запуском Claude. */
    static boolean checkCountry(Context ctx) {
        return prefs(ctx).getBoolean(KEY_CHECK_COUNTRY, true);
    }

    static void setCheckCountry(Context ctx, boolean value) {
        prefs(ctx).edit().putBoolean(KEY_CHECK_COUNTRY, value).apply();
    }

    /**
     * Выдано ли разрешение на VPN. Храним сами: VpnService.prepare() при уже выданном
     * разрешении делает это приложение активным VPN и отключает ваш VPN, поэтому
     * вызывать его можно только когда ваш VPN выключен.
     */
    static boolean vpnConsent(Context ctx) {
        // в прошлой версии флага не было: если блокировка была включена, разрешение уже есть
        return prefs(ctx).getBoolean(KEY_VPN_CONSENT, netBlock(ctx));
    }

    static void setVpnConsent(Context ctx, boolean value) {
        prefs(ctx).edit().putBoolean(KEY_VPN_CONSENT, value).apply();
    }

    /** Заворачивать в блокировщик все приложения, а не только Claude и браузеры. */
    static boolean netBlockAllApps(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ALL_APPS, false);
    }

    static void setNetBlockAllApps(Context ctx, boolean value) {
        prefs(ctx).edit().putBoolean(KEY_ALL_APPS, value).apply();
    }

    /** IP-адреса доменов Claude, полученные при включённом VPN. */
    static Set<String> cachedIps(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY_CACHED_IPS, new HashSet<>()));
    }

    static long cachedIpsAt(Context ctx) {
        return prefs(ctx).getLong(KEY_CACHED_AT, 0);
    }

    static void setCachedIps(Context ctx, Set<String> ips, long now) {
        prefs(ctx).edit().putStringSet(KEY_CACHED_IPS, ips).putLong(KEY_CACHED_AT, now).apply();
    }
}
