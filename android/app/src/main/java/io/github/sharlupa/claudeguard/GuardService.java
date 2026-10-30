package io.github.sharlupa.claudeguard;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.Set;

/**
 * Служба специальных возможностей: следит, какое приложение / сайт открыт, и если
 * это Claude, а VPN не включён — уводит на домашний экран и показывает экран блокировки.
 */
public class GuardService extends AccessibilityService {
    private static final long CONTENT_THROTTLE_MS = 400;
    private static final long BLOCK_COOLDOWN_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanRunnable = this::scanWindows;
    private final Runnable refreshRunnable = this::refreshVpn;

    private Set<String> browsers;
    private volatile boolean vpnActive;
    private long lastScanAt;
    private long lastBlockAt;

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    onNetworkChanged();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    onNetworkChanged();
                }

                @Override
                public void onLost(Network network) {
                    onNetworkChanged();
                }
            };

    private final BroadcastReceiver packagesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            browsers = ClaudeTargets.findBrowsers(GuardService.this);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        browsers = ClaudeTargets.findBrowsers(this);
        vpnActive = VpnState.isVpnActive(this);

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        cm.registerNetworkCallback(VpnState.allNetworksRequest(), networkCallback, handler);

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addDataScheme("package");
        registerReceiver(packagesReceiver, pkgFilter, Context.RECEIVER_NOT_EXPORTED);

        scheduleScan(0);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        } catch (IllegalArgumentException ignored) {
            // не был зарегистрирован
        }
        try {
            unregisterReceiver(packagesReceiver);
        } catch (IllegalArgumentException ignored) {
            // не был зарегистрирован
        }
        super.onDestroy();
    }

    private void onNetworkChanged() {
        refreshVpn();
        // состояние сетей меняется не мгновенно — перепроверяем ещё раз чуть позже
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, 1000);
    }

    private void refreshVpn() {
        vpnActive = VpnState.isVpnActive(this);
        // Если VPN отвалился, пока Claude открыт, — заблокировать сразу.
        if (!vpnActive) scheduleScan(300);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (vpnActive || !Prefs.isEnabled(this)) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || getPackageName().contentEquals(pkg)) return;

        boolean claudeApp = ClaudeTargets.isClaudeApp(pkg);
        boolean browser = browsers != null && ClaudeTargets.isBrowser(browsers, pkg);
        if (!claudeApp && !browser) return;

        if (claudeApp || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scheduleScan(0);
        } else {
            long wait = CONTENT_THROTTLE_MS - (SystemClock.elapsedRealtime() - lastScanAt);
            scheduleScan(Math.max(0, wait));
        }
    }

    @Override
    public void onInterrupt() {}

    private void scheduleScan(long delayMs) {
        if (delayMs > 0 && handler.hasCallbacks(scanRunnable)) return;
        handler.removeCallbacks(scanRunnable);
        handler.postDelayed(scanRunnable, delayMs);
    }

    /** Проверяет все видимые окна приложений (включая разделённый экран). */
    private void scanWindows() {
        lastScanAt = SystemClock.elapsedRealtime();
        if (vpnActive || !Prefs.isEnabled(this)) return;
        boolean checkBrowsers = Prefs.blockBrowsers(this);

        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            CharSequence pkg = root.getPackageName();
            if (ClaudeTargets.isClaudeApp(pkg)) {
                block(pkg);
                return;
            }
            if (checkBrowsers && ClaudeTargets.isBrowser(browsers, pkg)
                    && ClaudeTargets.browserShowsClaude(root)) {
                block(pkg);
                return;
            }
        }
    }

    private void block(CharSequence pkg) {
        // перепроверяем VPN напрямую, а не по закэшированному значению
        if (VpnState.isVpnActive(this)) {
            vpnActive = true;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBlockAt < BLOCK_COOLDOWN_MS) return;
        lastBlockAt = now;

        performGlobalAction(GLOBAL_ACTION_HOME);
        Intent intent = new Intent(this, BlockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BlockActivity.EXTRA_PACKAGE, pkg.toString());
        startActivity(intent);
    }
}
