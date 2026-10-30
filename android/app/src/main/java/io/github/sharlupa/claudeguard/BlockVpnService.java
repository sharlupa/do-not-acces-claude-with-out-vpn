package io.github.sharlupa.claudeguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * «VPN-блокировщик» — фоновая служба с уведомлением.
 *
 * Пока ваш VPN выключен, занимает VPN-слот Android и заворачивает в себя только адреса
 * Anthropic. Пакеты к ним никто не читает, поэтому они не уходят в сеть. Весь остальной
 * трафик идёт как обычно. Когда вы включаете свой VPN, Android отключает туннель
 * блокировщика (onRevoke), а служба ждёт, пока ваш VPN снова выключится.
 */
public class BlockVpnService extends VpnService {
    private static final String TAG = "ClaudeVpnGuard";
    private static final String ACTION_START = "io.github.sharlupa.claudeguard.START_BLOCKER";
    private static final String ACTION_STOP = "io.github.sharlupa.claudeguard.STOP_BLOCKER";
    private static final String CHANNEL_ID = "guard";
    private static final int NOTIFICATION_ID = 1;

    /** Собственные сети Anthropic (AS399358) — те же, что в Linux-версии. */
    private static final String[][] STATIC_ROUTES = {
            {"160.79.104.0", "23"},
            {"2607:6bc0::", "48"},
    };
    private static final String TUN_ADDRESS_V4 = "10.215.104.1";
    private static final String TUN_ADDRESS_V6 = "fd00:c1a0:de::1";

    /** Пауза перед подъёмом блокировщика после пропажи VPN (VPN мог просто переподключаться). */
    private static final long NET_DEBOUNCE_MS = 1500;
    /** Сколько ждать, пока ваш VPN подключится, после того как он вытеснил блокировщик. */
    private static final long REVOKE_GRACE_MS = 20_000;
    /** Повтор при ошибке (например, у другого приложения включён «Постоянный VPN»). */
    private static final long RETRY_MS = 60_000;
    private static final long IP_CACHE_TTL_MS = 6 * 60 * 60 * 1000L;

    private HandlerThread worker;
    private Handler handler;
    private final Runnable evaluateRunnable = this::evaluate;
    private ConnectivityManager cm;

    private ParcelFileDescriptor tun;
    private Config activeConfig;
    private long lastRevokedAt;
    private long lastFailAt;
    private int lastStatus;

    /** Что заложено в текущий туннель — чтобы пересоздавать его только при изменениях. */
    private static final class Config {
        final List<InetAddress> dns;
        final Set<String> apps;
        final boolean allApps;
        final Set<String> extraIps;

        Config(List<InetAddress> dns, Set<String> apps, boolean allApps, Set<String> extraIps) {
            this.dns = dns;
            this.apps = apps;
            this.allApps = allApps;
            this.extraIps = extraIps;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Config)) return false;
            Config c = (Config) o;
            return allApps == c.allApps && dns.equals(c.dns) && apps.equals(c.apps)
                    && extraIps.equals(c.extraIps);
        }

        @Override
        public int hashCode() {
            return dns.hashCode() ^ apps.hashCode() ^ extraIps.hashCode() ^ (allApps ? 1 : 0);
        }
    }

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    schedule(NET_DEBOUNCE_MS);
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    schedule(NET_DEBOUNCE_MS);
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                    schedule(NET_DEBOUNCE_MS);
                }

                @Override
                public void onLost(Network network) {
                    schedule(NET_DEBOUNCE_MS);
                }
            };

    private final BroadcastReceiver packagesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            schedule(0); // новый браузер — пересоздать туннель с ним
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (prefs, key) -> schedule(0);

    // ---------------------------------------------------------------------------------
    // Управление из приложения
    // ---------------------------------------------------------------------------------

    static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, BlockVpnService.class).setAction(ACTION_START));
    }

    static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, BlockVpnService.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        // START или перезапуск системой (intent == null)
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification(R.string.notif_starting),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (!Prefs.netBlock(this)) {
            shutdown();
            return START_NOT_STICKY;
        }
        init();
        schedule(0);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return SERVICE_INTERFACE.equals(intent.getAction()) ? super.onBind(intent) : null;
    }

    private void init() {
        if (worker != null) return;
        cm = getSystemService(ConnectivityManager.class);
        worker = new HandlerThread("net-guard");
        worker.start();
        handler = new Handler(worker.getLooper());

        cm.registerNetworkCallback(VpnState.allNetworksRequest(), networkCallback, handler);
        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addDataScheme("package");
        registerReceiver(packagesReceiver, pkgFilter, Context.RECEIVER_NOT_EXPORTED);
        Prefs.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener);
    }

    private void shutdown() {
        if (worker != null) {
            handler.removeCallbacksAndMessages(null);
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
                // не был зарегистрирован
            }
            unregisterReceiver(packagesReceiver);
            Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener);
            worker.quitSafely();
            worker = null;
        }
        closeTun();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (worker != null) shutdown();
        super.onDestroy();
    }

    /** Ваш VPN занял слот. Служба продолжает работать и ждёт, пока он снова выключится. */
    @Override
    public void onRevoke() {
        synchronized (this) {
            lastRevokedAt = SystemClock.elapsedRealtime();
        }
        closeTun();
        schedule(NET_DEBOUNCE_MS);
        // super.onRevoke() не вызываем: он остановил бы службу
    }

    // ---------------------------------------------------------------------------------
    // Логика (выполняется в фоновом потоке)
    // ---------------------------------------------------------------------------------

    private void schedule(long delayMs) {
        Handler h = handler;
        if (h == null) return;
        h.removeCallbacks(evaluateRunnable);
        h.postDelayed(evaluateRunnable, delayMs);
    }

    private void evaluate() {
        if (!Prefs.netBlock(this)) {
            closeTun();
            return;
        }
        if (VpnState.isVpnActive(this)) {
            refreshIpCache();
            closeTun();
            setStatus(R.string.notif_user_vpn);
            return;
        }
        if (prepare(this) != null) {
            closeTun();
            setStatus(R.string.notif_no_permission);
            return;
        }

        long now = SystemClock.elapsedRealtime();
        long revokedAt;
        synchronized (this) {
            revokedAt = lastRevokedAt;
        }
        if (revokedAt != 0 && now - revokedAt < REVOKE_GRACE_MS) {
            // ваш VPN только что занял слот и ещё подключается — не мешаем ему
            setStatus(R.string.notif_waiting_vpn);
            schedule(REVOKE_GRACE_MS - (now - revokedAt));
            return;
        }
        if (lastFailAt != 0 && now - lastFailAt < RETRY_MS && !isUp()) {
            schedule(RETRY_MS - (now - lastFailAt));
            return;
        }

        Set<String> apps = ClaudeTargets.findBrowsers(this);
        apps.add(ClaudeTargets.CLAUDE_PACKAGE);
        Config config = new Config(underlyingDns(), apps, Prefs.netBlockAllApps(this),
                Prefs.cachedIps(this));
        if (startTunnel(config)) {
            lastFailAt = 0;
            setStatus(R.string.notif_blocking);
        } else {
            lastFailAt = now;
            setStatus(R.string.notif_failed);
            schedule(RETRY_MS);
        }
    }

    private synchronized boolean isUp() {
        return tun != null;
    }

    /** Поднимает (или пересоздаёт при изменившихся настройках) туннель. */
    private synchronized boolean startTunnel(Config config) {
        if (tun != null && config.equals(activeConfig)) return true;

        Builder b = new Builder()
                .setSession(getString(R.string.vpn_session))
                .setConfigureIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setMetered(false)
                .setBlocking(false)
                .addAddress(TUN_ADDRESS_V4, 32)
                .addAddress(TUN_ADDRESS_V6, 128);
        for (String[] r : STATIC_ROUTES) b.addRoute(r[0], Integer.parseInt(r[1]));
        for (String ip : config.extraIps) {
            try {
                InetAddress addr = InetAddress.getByName(ip);
                if (isStaticRange(addr)) continue;
                b.addRoute(addr, addr instanceof Inet4Address ? 32 : 128);
            } catch (IOException | IllegalArgumentException e) {
                Log.w(TAG, "bad cached address " + ip, e);
            }
        }
        // DNS остаётся тем же, что у обычной сети (запросы идут мимо туннеля)
        for (InetAddress dns : config.dns) b.addDnsServer(dns);

        try {
            if (config.allApps) {
                b.addDisallowedApplication(getPackageName());
            } else {
                int added = 0;
                for (String pkg : config.apps) {
                    try {
                        b.addAllowedApplication(pkg);
                        added++;
                    } catch (PackageManager.NameNotFoundException ignored) {
                        // приложение удалили
                    }
                }
                // пустой список означал бы «все приложения»
                if (added == 0) return false;
            }

            ParcelFileDescriptor fd = b.establish();
            if (fd == null) return false;
            closeTunLocked();
            tun = fd;
            activeConfig = config;
            // Из туннеля ничего не читаем: пакеты к Anthropic просто отбрасываются ядром.
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            // например, у другого приложения включён «Постоянный VPN»
            Log.w(TAG, "establish failed", e);
            return false;
        }
    }

    private synchronized void closeTun() {
        closeTunLocked();
    }

    private void closeTunLocked() {
        activeConfig = null;
        if (tun == null) return;
        try {
            tun.close();
        } catch (IOException ignored) {
            // уже закрыт
        }
        tun = null;
    }

    /** DNS-серверы обычной сети (само приложение в туннель не входит). */
    private List<InetAddress> underlyingDns() {
        Network active = cm.getActiveNetwork();
        LinkProperties lp = active == null ? null : cm.getLinkProperties(active);
        return lp == null ? Collections.emptyList() : lp.getDnsServers();
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
                    if (!isStaticRange(addr)) ips.add(addr.getHostAddress());
                }
            } catch (UnknownHostException ignored) {
                // нет сети или домен не резолвится
            }
        }
        Prefs.setCachedIps(this, ips, now);
    }

    static boolean isStaticRange(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            return (b[0] & 0xff) == 160 && (b[1] & 0xff) == 79 && (b[2] & 0xfe) == 104;
        }
        return (b[0] & 0xff) == 0x26 && (b[1] & 0xff) == 0x07 && (b[2] & 0xff) == 0x6b
                && (b[3] & 0xff) == 0xc0 && b[4] == 0 && b[5] == 0;
    }

    // ---------------------------------------------------------------------------------
    // Уведомление
    // ---------------------------------------------------------------------------------

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(int textRes) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(textRes))
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void setStatus(int textRes) {
        if (textRes == lastStatus) return;
        lastStatus = textRes;
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, buildNotification(textRes));
    }
}
