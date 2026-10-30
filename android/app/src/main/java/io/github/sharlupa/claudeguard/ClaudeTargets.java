package io.github.sharlupa.claudeguard;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Что считается «Claude»: приложение Anthropic, браузеры и домены Claude. */
final class ClaudeTargets {
    private ClaudeTargets() {}

    static final String CLAUDE_PACKAGE = "com.anthropic.claude";
    private static final String CLAUDE_PACKAGE_PREFIX = "com.anthropic.";

    private static final Set<String> KNOWN_BROWSERS = new HashSet<>(Arrays.asList(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "org.chromium.chrome", "com.google.android.apps.chrome",
            "com.brave.browser", "com.brave.browser_beta", "com.brave.browser_nightly",
            "com.microsoft.emmx", "com.microsoft.emmx.beta",
            "com.opera.browser", "com.opera.browser.beta", "com.opera.mini.native", "com.opera.gx",
            "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
            "org.mozilla.focus", "org.mozilla.klar", "org.mozilla.fennec_fdroid",
            "io.github.forkmaintainers.iceraven", "us.spotco.fennec_dos",
            "org.torproject.torbrowser",
            "com.duckduckgo.mobile.android", "com.sec.android.app.sbrowser",
            "com.sec.android.app.sbrowser.beta", "com.vivaldi.browser", "com.kiwibrowser.browser",
            "com.yandex.browser", "com.yandex.browser.beta", "com.yandex.browser.lite",
            "ru.yandex.searchplugin", "com.mi.globalbrowser", "com.android.browser",
            "com.huawei.browser", "com.heytap.browser", "com.UCMobile.intl",
            "app.vanadium.browser", "org.bromite.bromite", "org.cromite.cromite",
            "mark.via.gp", "com.ecosia.android", "com.cloudmosa.puffinFree",
            "com.google.android.googlequicksearchbox"));

    /** Домены, чьи IP дополнительно заворачиваются в VPN-блокировщик (размещены у Anthropic). */
    static final String[] FIREWALL_DOMAINS = {
            "claude.ai", "claude.com", "api.anthropic.com", "platform.claude.com",
            "console.anthropic.com",
    };

    static boolean isClaudeApp(CharSequence pkg) {
        return pkg != null && pkg.toString().startsWith(CLAUDE_PACKAGE_PREFIX);
    }

    /** Известные браузеры + всё, что умеет открывать https-ссылки. */
    static Set<String> findBrowsers(Context ctx) {
        Set<String> result = new HashSet<>(KNOWN_BROWSERS);
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        PackageManager pm = ctx.getPackageManager();
        for (ResolveInfo ri : pm.queryIntentActivities(view,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL))) {
            if (ri.activityInfo != null) result.add(ri.activityInfo.packageName);
        }
        result.remove(ctx.getPackageName());
        return result;
    }

    static boolean isBrowser(Set<String> browsers, CharSequence pkg) {
        if (pkg == null) return false;
        String p = pkg.toString();
        return browsers.contains(p) || p.startsWith("org.chromium.webapk");
    }
}
