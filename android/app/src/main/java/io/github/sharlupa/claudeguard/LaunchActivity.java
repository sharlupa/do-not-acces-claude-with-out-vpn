package io.github.sharlupa.claudeguard;

import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Значок «Claude (VPN)». Открывает приложение Claude, только если:
 * 1) включён VPN и 2) (по умолчанию) интернет идёт из страны, где Claude доступен.
 * Иначе просит включить VPN / сменить сервер и открывает Claude сам, как только всё в порядке.
 */
public class LaunchActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** Номер текущей проверки — устаревшие результаты игнорируются. */
    private int generation;
    private boolean callbackRegistered;
    private boolean launched;

    private TextView title;
    private TextView text;
    private View openVpn;
    private View retry;

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    scheduleCheck(300);
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    scheduleCheck(300);
                }

                @Override
                public void onLost(Network network) {
                    scheduleCheck(500);
                }
            };
    private final Runnable checkRunnable = this::check;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);
        Ui.applyInsets(findViewById(R.id.root));
        title = findViewById(R.id.title);
        text = findViewById(R.id.text);
        openVpn = findViewById(R.id.open_vpn);
        retry = findViewById(R.id.retry);
        openVpn.setOnClickListener(v -> VpnState.openVpn(this));
        retry.setOnClickListener(v -> check());
        findViewById(R.id.settings).setOnClickListener(v ->
                startActivity(new Intent(this, MainActivity.class)));
    }

    @Override
    protected void onStart() {
        super.onStart();
        getSystemService(ConnectivityManager.class)
                .registerNetworkCallback(VpnState.allNetworksRequest(), networkCallback, handler);
        callbackRegistered = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        check();
    }

    @Override
    protected void onStop() {
        if (callbackRegistered) {
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
            callbackRegistered = false;
        }
        handler.removeCallbacksAndMessages(null);
        generation++; // результаты незавершённых проверок больше не нужны
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void scheduleCheck(long delayMs) {
        handler.removeCallbacks(checkRunnable);
        handler.postDelayed(checkRunnable, delayMs);
    }

    private void check() {
        if (launched || isFinishing()) return;
        int gen = ++generation;
        if (claudeLaunchIntent() == null) {
            show(R.string.launch_no_claude_title, getString(R.string.launch_no_claude_text), false, false);
            return;
        }
        if (!VpnState.isVpnActive(this)) {
            show(R.string.launch_no_vpn_title, getString(R.string.launch_no_vpn_text), true, false);
            return;
        }
        if (!Prefs.checkCountry(this)) {
            launchClaude();
            return;
        }
        show(R.string.launch_checking_title, getString(R.string.launch_checking_text), false, false);
        executor.execute(() -> {
            CountryCheck.Result result = CountryCheck.check();
            handler.post(() -> onCountry(gen, result));
        });
    }

    private void onCountry(int gen, CountryCheck.Result result) {
        if (gen != generation || launched || isFinishing()) return;
        if (result == null) {
            show(R.string.launch_check_failed_title, getString(R.string.launch_check_failed_text),
                    true, true);
        } else if (!Countries.isSupported(result.country)) {
            show(R.string.launch_wrong_country_title,
                    getString(R.string.launch_wrong_country_text,
                            Countries.name(result.country), result.ip),
                    true, true);
        } else {
            launchClaude();
        }
    }

    private void show(int titleRes, CharSequence body, boolean showVpn, boolean showRetry) {
        title.setText(titleRes);
        text.setText(body);
        openVpn.setVisibility(showVpn ? View.VISIBLE : View.GONE);
        retry.setVisibility(showRetry ? View.VISIBLE : View.GONE);
    }

    private void launchClaude() {
        Intent claude = claudeLaunchIntent();
        if (claude == null) return;
        launched = true;
        startActivity(claude.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    private Intent claudeLaunchIntent() {
        return getPackageManager().getLaunchIntentForPackage(ClaudeTargets.CLAUDE_PACKAGE);
    }
}
