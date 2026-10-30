package io.github.sharlupa.claudeguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Главный экран: состояние защиты и настройки. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView serviceStatus;
    private TextView vpnStatus;
    private Button enableService;
    private Button vpnAppButton;

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    updateStatus();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    updateStatus();
                }

                @Override
                public void onLost(Network network) {
                    handler.postDelayed(MainActivity.this::updateStatus, 500);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        BlockActivity.applyInsets(findViewById(R.id.root));

        serviceStatus = findViewById(R.id.service_status);
        vpnStatus = findViewById(R.id.vpn_status);
        enableService = findViewById(R.id.enable_service);
        vpnAppButton = findViewById(R.id.vpn_app);

        Switch enabled = findViewById(R.id.switch_enabled);
        enabled.setChecked(Prefs.isEnabled(this));
        enabled.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setEnabled(this, checked);
            updateStatus();
        });

        Switch browsers = findViewById(R.id.switch_browsers);
        browsers.setChecked(Prefs.blockBrowsers(this));
        browsers.setOnCheckedChangeListener((b, checked) -> Prefs.setBlockBrowsers(this, checked));

        enableService.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.app_info).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null))));
        vpnAppButton.setOnClickListener(v -> chooseVpnApp());
        findViewById(R.id.open_vpn).setOnClickListener(v -> VpnState.openVpn(this));
    }

    @Override
    protected void onStart() {
        super.onStart();
        getSystemService(ConnectivityManager.class)
                .registerNetworkCallback(VpnState.allNetworksRequest(), networkCallback, handler);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onStop() {
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        handler.removeCallbacksAndMessages(null);
        super.onStop();
    }

    private void updateStatus() {
        boolean serviceOn = isServiceEnabled();
        boolean active = serviceOn && Prefs.isEnabled(this);
        serviceStatus.setText(active ? R.string.status_protection_on
                : serviceOn ? R.string.status_protection_paused : R.string.status_service_off);
        serviceStatus.setTextColor(getColor(active ? R.color.ok : R.color.bad));
        enableService.setText(serviceOn ? R.string.btn_service_settings : R.string.btn_enable_service);

        boolean vpn = VpnState.isVpnActive(this);
        vpnStatus.setText(vpn ? R.string.status_vpn_on : R.string.status_vpn_off);
        vpnStatus.setTextColor(getColor(vpn ? R.color.ok : R.color.bad));

        String pkg = Prefs.vpnApp(this);
        CharSequence label = pkg == null ? null : appLabel(pkg);
        vpnAppButton.setText(label == null ? getString(R.string.btn_choose_vpn_app)
                : getString(R.string.btn_vpn_app, label));
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(this, GuardService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        for (String s : splitter) {
            if (me.equals(ComponentName.unflattenFromString(s))) return true;
        }
        return false;
    }

    private CharSequence appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Список приложений, которые реализуют VpnService. */
    private void chooseVpnApp() {
        PackageManager pm = getPackageManager();
        Map<String, CharSequence> apps = new LinkedHashMap<>();
        List<ResolveInfo> services = pm.queryIntentServices(
                new Intent("android.net.VpnService"),
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA));
        for (ResolveInfo ri : services) {
            String pkg = ri.serviceInfo.packageName;
            if (pm.getLaunchIntentForPackage(pkg) == null) continue;
            apps.put(pkg, ri.serviceInfo.applicationInfo.loadLabel(pm));
        }
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.no_vpn_apps, Toast.LENGTH_LONG).show();
            return;
        }
        List<String> pkgs = new ArrayList<>(apps.keySet());
        pkgs.sort((a, b) -> apps.get(a).toString().compareToIgnoreCase(apps.get(b).toString()));
        CharSequence[] labels = new CharSequence[pkgs.size() + 1];
        for (int i = 0; i < pkgs.size(); i++) labels[i] = apps.get(pkgs.get(i));
        labels[pkgs.size()] = getString(R.string.vpn_system_settings);

        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_choose_vpn_app)
                .setItems(labels, (d, which) -> {
                    Prefs.setVpnApp(this, which < pkgs.size() ? pkgs.get(which) : null);
                    updateStatus();
                })
                .show();
    }
}
