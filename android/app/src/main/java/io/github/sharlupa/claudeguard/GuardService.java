package io.github.sharlupa.claudeguard;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.VpnService;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Служба специальных возможностей — работает в фоне постоянно.
 *
 * 1. Экран: если открыто приложение Claude или claude.ai в браузере, а VPN не включён, —
 *    уводит на домашний экран и показывает экран блокировки.
 * 2. Сеть: пока ваш VPN выключен, держит поднятым {@link BlockVpnService}, который
 *    не выпускает в сеть ни одного пакета к Anthropic (в том числе фоновые запросы).
 */
public class GuardService extends AccessibilityService {
    private static final long CONTENT_THROTTLE_MS = 400;
    private static final long BLOCK_COOLDOWN_MS = 1500;
    /** Пауза перед подъёмом блокировщика после пропажи VPN (VPN мог просто переподключаться). */
    private static final long NET_DEBOUNCE_MS = 1500;
    /** Сколько ждать, пока ваш VPN подключится, после того как он вытеснил блокировщик. */
    private static final long REVOKE_GRACE_MS = 20_000;
    /** Повтор при ошибке (например, у другого приложения включён «Постоянный VPN»). */
    private static final long RETRY_MS = 60_000;
    private static final long IP_CACHE_TTL_MS = 6 * 60 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanRunnable = this::scanWindows;
    private final Runnable refreshRunnable = this::refreshVpn;

    private HandlerThread worker;
    private Handler workerHandler;
    private final Runnable evaluateRunnable = this::evaluateNetBlock;

    private Set<String> browsers;
    private volatile boolean vpnActive;
    private long lastScanAt;
    private long lastBlockAt;

    private volatile BlockVpnService blocker;
    private boolean blockerBound;
    private long lastFailAt;

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
                public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
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
            scheduleEvaluate(0);
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (prefs, key) -> scheduleEvaluate(0);

    private final ServiceConnection blockerConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            blocker = ((BlockVpnService.LocalBinder) service).service();
            scheduleEvaluate(0);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            blocker = null;
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        worker = new HandlerThread("net-guard");
        worker.start();
        workerHandler = new Handler(worker.getLooper());

        browsers = ClaudeTargets.findBrowsers(this);
        vpnActive = VpnState.isVpnActive(this);

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        cm.registerNetworkCallback(VpnState.allNetworksRequest(), networkCallback, handler);

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addDataScheme("package");
        registerReceiver(packagesReceiver, pkgFilter, Context.RECEIVER_NOT_EXPORTED);
        Prefs.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener);

        scheduleScan(0);
        scheduleEvaluate(0);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener);
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
        // Блокировщик оставляем поднятым (fail-closed); выключается он переключателем
        // в приложении или когда вы включаете свой VPN.
        if (blockerBound) unbindService(blockerConnection);
        if (worker != null) worker.quitSafely();
        super.onDestroy();
    }

    private void onNetworkChanged() {
        refreshVpn();
        // состояние сетей меняется не мгновенно — перепроверяем ещё раз чуть позже
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, 1000);
        scheduleEvaluate(NET_DEBOUNCE_MS);
    }

    private void refreshVpn() {
        vpnActive = VpnState.isVpnActive(this);
        // Если VPN отвалился, пока Claude открыт, — заблокировать сразу.
        if (!vpnActive) scheduleScan(300);
    }

    // ---------------------------------------------------------------------------------
    // Сеть: VPN-блокировщик
    // ---------------------------------------------------------------------------------

    private void scheduleEvaluate(long delayMs) {
        if (workerHandler == null) return;
        workerHandler.removeCallbacks(evaluateRunnable);
        workerHandler.postDelayed(evaluateRunnable, delayMs);
    }

    /** Решает, нужен ли сейчас VPN-блокировщик. Выполняется в фоновом потоке. */
    private void evaluateNetBlock() {
        boolean want = Prefs.isEnabled(this) && Prefs.netBlock(this);
        boolean userVpn = VpnState.isVpnActive(this);
        BlockVpnService b = blocker;

        if (userVpn) refreshIpCache();
        if (!want || userVpn) {
            if (b != null) b.stop();
            return;
        }
        if (VpnService.prepare(this) != null) return; // разрешение на VPN ещё не выдано

        long now = SystemClock.elapsedRealtime();
        long sinceRevoke = now - BlockVpnService.lastRevokedAt;
        if (BlockVpnService.lastRevokedAt != 0 && sinceRevoke < REVOKE_GRACE_MS) {
            // ваш VPN только что занял слот и ещё подключается — не мешаем ему
            scheduleEvaluate(REVOKE_GRACE_MS - sinceRevoke);
            return;
        }
        if (lastFailAt != 0 && now - lastFailAt < RETRY_MS) {
            scheduleEvaluate(RETRY_MS - (now - lastFailAt));
            return;
        }
        if (b == null) {
            if (!blockerBound) {
                blockerBound = bindService(new Intent(this, BlockVpnService.class),
                        blockerConnection, Context.BIND_AUTO_CREATE);
            }
            return; // продолжим в onServiceConnected
        }

        Set<String> apps = new HashSet<>(browsers);
        apps.add("com.anthropic.claude");
        BlockVpnService.Config config = new BlockVpnService.Config(
                BlockVpnService.underlyingDns(getSystemService(ConnectivityManager.class)),
                apps, Prefs.netBlockAllApps(this), Prefs.cachedIps(this));
        if (b.start(config)) {
            lastFailAt = 0;
        } else {
            lastFailAt = now;
            scheduleEvaluate(RETRY_MS);
        }
    }

    /** Обновляет IP доменов Claude — только через ваш VPN, чтобы DNS-запросы не утекали. */
    private void refreshIpCache() {
        long now = System.currentTimeMillis();
        if (now - Prefs.cachedIpsAt(this) < IP_CACHE_TTL_MS) return;
        Set<String> ips = new HashSet<>();
        for (String domain : ClaudeTargets.FIREWALL_DOMAINS) {
            try {
                for (InetAddress addr : InetAddress.getAllByName(domain)) {
                    if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                            || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) continue;
                    if (!BlockVpnService.isStaticRange(addr)) ips.add(addr.getHostAddress());
                }
            } catch (java.net.UnknownHostException ignored) {
                // нет сети или домен не резолвится
            }
        }
        Prefs.setCachedIps(this, ips, now);
    }

    // ---------------------------------------------------------------------------------
    // Экран
    // ---------------------------------------------------------------------------------

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
