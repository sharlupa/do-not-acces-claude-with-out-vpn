package io.github.sharlupa.claudeguard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

/** Экран «Claude заблокирован — включите VPN». */
public class BlockActivity extends Activity {
    static final String EXTRA_PACKAGE = "package";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String blockedPackage;
    private boolean finishing;

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    check();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    check();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_block);
        applyInsets(findViewById(R.id.root));
        blockedPackage = getIntent().getStringExtra(EXTRA_PACKAGE);

        Button vpn = findViewById(R.id.open_vpn);
        vpn.setOnClickListener(v -> VpnState.openVpn(this));
        findViewById(R.id.close).setOnClickListener(v -> goHome());

        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goHome);

        getSystemService(ConnectivityManager.class)
                .registerNetworkCallback(VpnState.allNetworksRequest(), networkCallback, handler);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        blockedPackage = intent.getStringExtra(EXTRA_PACKAGE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        check();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        super.onDestroy();
    }

    /** Как только VPN включился — закрываемся и возвращаем пользователя в Claude. */
    private void check() {
        if (finishing || !VpnState.isVpnActive(this)) return;
        finishing = true;
        TextView title = findViewById(R.id.title);
        title.setText(R.string.block_vpn_on);
        handler.postDelayed(() -> {
            if (blockedPackage != null) {
                Intent launch = getPackageManager().getLaunchIntentForPackage(blockedPackage);
                if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            finish();
        }, 600);
    }

    private void goHome() {
        startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    static void applyInsets(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
    }
}
