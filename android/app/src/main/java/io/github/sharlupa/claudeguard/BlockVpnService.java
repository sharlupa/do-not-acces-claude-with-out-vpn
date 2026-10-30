package io.github.sharlupa.claudeguard;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * «VPN-блокировщик». Пока ваш VPN выключен, занимает VPN-слот Android и заворачивает
 * в себя только адреса Anthropic. Пакеты к ним никто не читает, поэтому они не уходят
 * в сеть. Весь остальной трафик идёт как обычно.
 *
 * Когда вы включаете свой VPN, Android сам отключает блокировщик (onRevoke).
 */
public class BlockVpnService extends VpnService {
    private static final String TAG = "ClaudeVpnGuard";

    /** Собственные сети Anthropic (AS399358) — те же, что в Linux-версии. */
    private static final String[][] STATIC_ROUTES = {
            {"160.79.104.0", "23"},
            {"2607:6bc0::", "48"},
    };
    private static final String TUN_ADDRESS_V4 = "10.215.104.1";
    private static final String TUN_ADDRESS_V6 = "fd00:c1a0:de::1";

    static final String ACTION_STOP = "io.github.sharlupa.claudeguard.STOP_BLOCKER";

    /** Время последнего отключения блокировщика чужим VPN (elapsedRealtime). */
    static volatile long lastRevokedAt;

    private final IBinder binder = new LocalBinder();
    private ParcelFileDescriptor tun;
    private Config activeConfig;

    /** Что заложено в текущий туннель — чтобы пересоздавать его только при изменениях. */
    static final class Config {
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

    final class LocalBinder extends Binder {
        BlockVpnService service() {
            return BlockVpnService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) return super.onBind(intent);
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stop();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    synchronized boolean isUp() {
        return tun != null;
    }

    /** Поднимает (или пересоздаёт при изменившихся настройках) туннель. */
    synchronized boolean start(Config config) {
        if (tun != null && config.equals(activeConfig)) return true;
        if (prepare(this) != null) return false; // нет разрешения на VPN

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
            closeTun();
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

    synchronized void stop() {
        closeTun();
        activeConfig = null;
    }

    private void closeTun() {
        if (tun == null) return;
        try {
            tun.close();
        } catch (IOException ignored) {
            // уже закрыт
        }
        tun = null;
    }

    @Override
    public void onRevoke() {
        lastRevokedAt = SystemClock.elapsedRealtime();
        stop();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    /** DNS-серверы текущей обычной сети. */
    static List<InetAddress> underlyingDns(ConnectivityManager cm) {
        Network active = cm.getActiveNetwork();
        LinkProperties lp = active == null ? null : cm.getLinkProperties(active);
        return lp == null ? Collections.emptyList() : lp.getDnsServers();
    }

    static boolean isStaticRange(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            return (b[0] & 0xff) == 160 && (b[1] & 0xff) == 79 && (b[2] & 0xfe) == 104;
        }
        return (b[0] & 0xff) == 0x26 && (b[1] & 0xff) == 0x07 && (b[2] & 0xff) == 0x6b
                && (b[3] & 0xff) == 0xc0 && b[4] == 0 && b[5] == 0;
    }
}
