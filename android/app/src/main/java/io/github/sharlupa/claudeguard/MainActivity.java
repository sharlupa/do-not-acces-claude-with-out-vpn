package io.github.sharlupa.claudeguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
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
    private static final int REQUEST_VPN = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;
    /** Элементы блокировки экрана — только в полной версии (со спец. возможностями). */
    private static final int[] SCREEN_GUARD_VIEWS = {
            R.id.service_status, R.id.switch_enabled, R.id.switch_browsers,
            R.id.enable_service, R.id.app_info,
    };

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView serviceStatus;
    private TextView vpnStatus;
    private TextView netStatus;
    private Switch netBlockSwitch;
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
        Ui.applyInsets(findViewById(R.id.root));

        serviceStatus = findViewById(R.id.service_status);
        vpnStatus = findViewById(R.id.vpn_status);
        netStatus = findViewById(R.id.net_status);
        enableService = findViewById(R.id.enable_service);
        vpnAppButton = findViewById(R.id.vpn_app);

        Switch enabled = findViewById(R.id.switch_enabled);
        enabled.setChecked(Prefs.isEnabled(this));
        enabled.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setEnabled(this, checked);
            updateStatus();
        });

        Switch checkCountry = findViewById(R.id.switch_check_country);
        checkCountry.setChecked(Prefs.checkCountry(this));
        checkCountry.setOnCheckedChangeListener((b, checked) -> Prefs.setCheckCountry(this, checked));

        Switch browsers = findViewById(R.id.switch_browsers);
        browsers.setChecked(Prefs.blockBrowsers(this));
        browsers.setOnCheckedChangeListener((b, checked) -> Prefs.setBlockBrowsers(this, checked));

        netBlockSwitch = findViewById(R.id.switch_net_block);
        netBlockSwitch.setChecked(Prefs.netBlock(this) && Prefs.vpnConsent(this));
        netBlockSwitch.setOnCheckedChangeListener((b, checked) -> onNetBlockToggled(checked));

        Switch allApps = findViewById(R.id.switch_all_apps);
        allApps.setChecked(Prefs.netBlockAllApps(this));
        allApps.setOnCheckedChangeListener((b, checked) -> Prefs.setNetBlockAllApps(this, checked));

        enableService.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.app_info).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null))));
        vpnAppButton.setOnClickListener(v -> chooseVpnApp());
        findViewById(R.id.open_vpn).setOnClickListener(v -> VpnState.openVpn(this));

        if (!BuildConfig.SCREEN_GUARD) {
            for (int id : SCREEN_GUARD_VIEWS) findViewById(id).setVisibility(View.GONE);
        }
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
        // служба могла быть остановлена системой — поднимаем её снова
        if (Prefs.netBlock(this) && Prefs.vpnConsent(this)) BlockVpnService.start(this);
        updateStatus();
    }

    @Override
    protected void onStop() {
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        handler.removeCallbacksAndMessages(null);
        super.onStop();
    }

    private void onNetBlockToggled(boolean checked) {
        if (!checked) {
            Prefs.setNetBlock(this, false);
            BlockVpnService.stop(this);
            updateStatus();
            return;
        }
        if (Prefs.vpnConsent(this)) {
            enableNetBlock();
        } else if (VpnState.isVpnActive(this)) {
            // Выдача разрешения на VPN всегда отключает текущий VPN — предупреждаем
            new AlertDialog.Builder(this)
                    .setTitle(R.string.consent_title)
                    .setMessage(R.string.consent_message)
                    .setPositiveButton(R.string.consent_continue, (d, w) -> requestConsent())
                    .setNegativeButton(android.R.string.cancel,
                            (d, w) -> netBlockSwitch.setChecked(false))
                    .setOnCancelListener(d -> netBlockSwitch.setChecked(false))
                    .show();
        } else {
            requestConsent();
        }
    }

    private void requestConsent() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            startActivityForResult(consent, REQUEST_VPN);
        } else {
            Prefs.setVpnConsent(this, true);
            enableNetBlock();
        }
    }

    private void enableNetBlock() {
        Prefs.setNetBlock(this, true);
        BlockVpnService.start(this);
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
        updateStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_VPN) return;
        if (resultCode == RESULT_OK) {
            Prefs.setVpnConsent(this, true);
            enableNetBlock();
        } else {
            Prefs.setNetBlock(this, false);
            netBlockSwitch.setChecked(false);
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show();
            updateStatus();
        }
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

        boolean netOk;
        if (vpn) {
            netStatus.setText(R.string.status_net_user_vpn);
            netOk = true;
        } else if (VpnState.isBlockerActive(this)) {
            netStatus.setText(R.string.status_net_blocking);
            netOk = true;
        } else if (Prefs.netBlock(this)) {
            netStatus.setText(R.string.status_net_waiting);
            netOk = false;
        } else {
            netStatus.setText(R.string.status_net_off);
            netOk = false;
        }
        netStatus.setTextColor(getColor(netOk ? R.color.ok : R.color.bad));

        String pkg = Prefs.vpnApp(this);
        CharSequence label = pkg == null ? null : appLabel(pkg);
        vpnAppButton.setText(label == null ? getString(R.string.btn_choose_vpn_app)
                : getString(R.string.btn_vpn_app, label));
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(getPackageName(),
                getPackageName() + ".GuardService");
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
