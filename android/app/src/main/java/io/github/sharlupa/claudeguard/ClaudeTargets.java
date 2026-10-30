package io.github.sharlupa.claudeguard;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Что считается «Claude»: приложение Anthropic и адреса Claude в браузерах. */
final class ClaudeTargets {
    private ClaudeTargets() {}

    private static final String CLAUDE_PACKAGE_PREFIX = "com.anthropic.";

    /** Адрес в строке браузера: claude.ai/..., https://www.anthropic.com, ... */
    private static final Pattern CLAUDE_URL = Pattern.compile(
            "^\\s*(?:https?://)?(?:[a-z0-9-]+\\.)*"
                    + "(?:claude\\.ai|claude\\.com|anthropic\\.com|claudeusercontent\\.com|claude\\.site)"
                    + "(?:[:/?#\\s]|$)",
            Pattern.CASE_INSENSITIVE);

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

    private static final int MAX_NODES = 800;

    static boolean isClaudeApp(CharSequence pkg) {
        return pkg != null && pkg.toString().startsWith(CLAUDE_PACKAGE_PREFIX);
    }

    static boolean isClaudeUrl(CharSequence text) {
        return text != null && text.length() < 2048 && CLAUDE_URL.matcher(text).find();
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

    /**
     * Ищет адрес Claude в интерфейсе браузера (строка адреса, вкладки, подсказки).
     * Содержимое самих страниц (WebView) не просматривается, поэтому ссылки на
     * claude.ai в результатах поиска или статьях не вызывают блокировку.
     */
    static boolean browserShowsClaude(AccessibilityNodeInfo root) {
        if (root == null) return false;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            CharSequence cls = node.getClassName();
            if (node != root && cls != null && "android.webkit.WebView".contentEquals(cls)) {
                continue;
            }
            if (isClaudeUrl(node.getText())) return true;
            if (looksLikeAddressBar(node) && isClaudeUrl(node.getContentDescription())) return true;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return false;
    }

    private static boolean looksLikeAddressBar(AccessibilityNodeInfo node) {
        String id = node.getViewIdResourceName();
        if (id == null) return node.isEditable();
        id = id.toLowerCase();
        return id.contains("url") || id.contains("omnibar") || id.contains("omnibox")
                || id.contains("address") || id.contains("location");
    }
}
