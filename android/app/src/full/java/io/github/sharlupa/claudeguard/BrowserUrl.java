package io.github.sharlupa.claudeguard;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.regex.Pattern;

/** Поиск адреса Claude в интерфейсе браузера (для блокировки экрана). */
final class BrowserUrl {
    private BrowserUrl() {}

    /** Адрес в строке браузера: claude.ai/..., https://www.anthropic.com, ... */
    private static final Pattern CLAUDE_URL = Pattern.compile(
            "^\\s*(?:https?://)?(?:[a-z0-9-]+\\.)*"
                    + "(?:claude\\.ai|claude\\.com|anthropic\\.com|claudeusercontent\\.com|claude\\.site)"
                    + "(?:[:/?#\\s]|$)",
            Pattern.CASE_INSENSITIVE);

    private static final int MAX_NODES = 800;

    static boolean isClaudeUrl(CharSequence text) {
        return text != null && text.length() < 2048 && CLAUDE_URL.matcher(text).find();
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
