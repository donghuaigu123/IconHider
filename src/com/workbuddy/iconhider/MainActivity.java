package com.workbuddy.iconhider;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 图标隐藏 v2.0 —— 自带桌面（Launcher）方案。
 *
 * 普通第三方 App 无权限禁用其他 App 的组件，因此改为：
 * 本应用作为桌面，桌面上的图标由用户自由勾选显示/隐藏。
 * 无需 Root、无需特殊权限、一次设为默认桌面永久生效。
 */
public class MainActivity extends Activity {

    private PackageManager pm;
    private SharedPreferences prefs;
    private final Set<String> hiddenPkgs = new HashSet<String>();

    private final List<AppInfo> allApps = new ArrayList<AppInfo>();
    private final List<AppInfo> shownApps = new ArrayList<AppInfo>();
    private final List<AppInfo> desktopApps = new ArrayList<AppInfo>();

    private View desktopPanel;
    private View managePanel;
    private GridView appGrid;
    private ListView manageList;
    private EditText searchBox;
    private TextView statusView;
    private TextView homeStatus;
    private GridAdapter gridAdapter;
    private ManageAdapter manageAdapter;
    private String keyword = "";
    private boolean managing = false;
    private boolean receiverRegistered = false;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private static class AppInfo {
        String pkg;
        String label;
        String labelLower;
        Drawable icon;
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadAppsAsync(); // 安装/卸载应用后自动刷新列表
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        pm = getPackageManager();
        prefs = getSharedPreferences("icon_hider_prefs", MODE_PRIVATE);
        hiddenPkgs.addAll(prefs.getStringSet("hidden", new HashSet<String>()));

        desktopPanel = findViewById(R.id.desktop_panel);
        managePanel = findViewById(R.id.manage_panel);
        appGrid = (GridView) findViewById(R.id.app_grid);
        manageList = (ListView) findViewById(R.id.list);
        searchBox = (EditText) findViewById(R.id.search);
        statusView = (TextView) findViewById(R.id.status);
        homeStatus = (TextView) findViewById(R.id.home_status);

        gridAdapter = new GridAdapter();
        manageAdapter = new ManageAdapter();
        appGrid.setAdapter(gridAdapter);
        manageList.setAdapter(manageAdapter);

        // 整行点击切换：开关只是状态显示，不再依赖 Switch 自身点击回调
        manageList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AppInfo info = shownApps.get(position);
                boolean toVisible = hiddenPkgs.contains(info.pkg);
                setAppVisible(info, toVisible);
            }
        });

        // 桌面图标点击 = 启动应用；长按 = 快速隐藏/显示菜单
        appGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AppInfo info = desktopApps.get(position);
                Intent launch = pm.getLaunchIntentForPackage(info.pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                } else {
                    Toast.makeText(MainActivity.this, "该应用无法启动", Toast.LENGTH_SHORT).show();
                }
            }
        });
        appGrid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                final AppInfo info = desktopApps.get(position);
                final boolean currentlyHidden = hiddenPkgs.contains(info.pkg);
                CharSequence[] actions = new CharSequence[] {
                        getString(currentlyHidden ? R.string.action_show : R.string.action_hide),
                        getString(R.string.action_app_info)
                };
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(info.label)
                        .setItems(actions, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                if (which == 0) setAppVisible(info, currentlyHidden);
                                else {
                                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                    intent.setData(Uri.parse("package:" + info.pkg));
                                    startActivity(intent);
                                }
                            }
                        }).show();
                return true;
            }
        });

        findViewById(R.id.btn_manage).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchMode(true); }
        });
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchMode(false); }
        });
        homeStatus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!isDefaultHome()) gotoHomeSettings();
            }
        });

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                keyword = s.toString().trim().toLowerCase(Locale.ROOT);
                refreshManageList();
            }
        });

        loadAppsAsync();
        updateHomeStatus();

        // 首次使用引导
        if (!prefs.getBoolean("guide_shown", false)) {
            prefs.edit().putBoolean("guide_shown", true).apply();
            if (!isDefaultHome()) showGuide();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateHomeStatus();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        try {
            registerReceiver(packageReceiver, filter);
            receiverRegistered = true;
        } catch (Exception ignored) { }
    }

    @Override
    protected void onPause() {
        if (receiverRegistered) {
            try { unregisterReceiver(packageReceiver); } catch (Exception ignored) { }
            receiverRegistered = false;
        }
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (managing) switchMode(false);
        // 桌面状态不退出（Launcher 标准行为），按 HOME 键即可回到系统层面
    }

    private void switchMode(boolean toManage) {
        managing = toManage;
        desktopPanel.setVisibility(toManage ? View.GONE : View.VISIBLE);
        managePanel.setVisibility(toManage ? View.VISIBLE : View.GONE);
        if (toManage) refreshManageList();
    }

    private void showGuide() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.guide_title)
                .setMessage(R.string.guide_message)
                .setPositiveButton(R.string.guide_ok, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        gotoHomeSettings();
                    }
                })
                .setNegativeButton(R.string.guide_later, null)
                .show();
    }

    private boolean isDefaultHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = pm.resolveActivity(home, 0);
        return ri != null && ri.activityInfo != null
                && getPackageName().equals(ri.activityInfo.packageName);
    }

    private void updateHomeStatus() {
        boolean isHome = isDefaultHome();
        homeStatus.setText(isHome ? R.string.home_ok : R.string.home_hint);
        homeStatus.setTextColor(isHome ? 0xFF2E7D45 : 0xFFB23A2B);
    }

    private void gotoHomeSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_HOME_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent chooser = Intent.makeMainSelectorActivity(
                        Intent.ACTION_MAIN, Intent.CATEGORY_HOME);
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(chooser);
            } catch (Exception e2) {
                Toast.makeText(this, R.string.set_home_fail, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void setAppVisible(AppInfo info, boolean visible) {
        if (visible) hiddenPkgs.remove(info.pkg);
        else hiddenPkgs.add(info.pkg);
        prefs.edit().putStringSet("hidden", new HashSet<String>(hiddenPkgs)).apply();
        Toast.makeText(this,
                getString(visible ? R.string.toast_shown : R.string.toast_hidden, info.label),
                Toast.LENGTH_SHORT).show();
        refreshDesktop();
        refreshManageList();
    }

    private void loadAppsAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
                String self = getPackageName();
                Set<String> seen = new HashSet<String>();
                List<AppInfo> result = new ArrayList<AppInfo>();
                for (ResolveInfo ri : ris) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || self.equals(pkg) || !seen.add(pkg)) continue;
                    AppInfo info = new AppInfo();
                    info.pkg = pkg;
                    CharSequence label = ri.loadLabel(pm);
                    info.label = label != null ? label.toString() : pkg;
                    info.labelLower = info.label.toLowerCase(Locale.ROOT);
                    info.icon = ri.loadIcon(pm);
                    result.add(info);
                }
                final List<AppInfo> loaded = result;
                ui.post(new Runnable() {
                    @Override public void run() {
                        allApps.clear();
                        allApps.addAll(loaded);
                        refreshDesktop();
                        refreshManageList();
                    }
                });
            }
        }, "app-loader").start();
    }

    private void refreshDesktop() {
        desktopApps.clear();
        for (AppInfo a : allApps) if (!hiddenPkgs.contains(a.pkg)) desktopApps.add(a);
        sort(desktopApps);
        gridAdapter.notifyDataSetChanged();
    }

    private void refreshManageList() {
        shownApps.clear();
        for (AppInfo a : allApps) {
            if (keyword.isEmpty() || a.labelLower.contains(keyword)
                    || a.pkg.toLowerCase(Locale.ROOT).contains(keyword)) {
                shownApps.add(a);
            }
        }
        sort(shownApps);
        manageAdapter.notifyDataSetChanged();
        statusView.setText(getString(R.string.status_format, allApps.size(), hiddenPkgs.size()));
    }

    private void sort(List<AppInfo> list) {
        Collections.sort(list, new Comparator<AppInfo>() {
            @Override public int compare(AppInfo a, AppInfo b) {
                // 管理列表中隐藏的排前面，方便找回；桌面列表按名称排序
                boolean ha = hiddenPkgs.contains(a.pkg), hb = hiddenPkgs.contains(b.pkg);
                if (ha != hb) return ha ? -1 : 1;
                return a.labelLower.compareTo(b.labelLower);
            }
        });
    }

    private class GridAdapter extends BaseAdapter {
        @Override public int getCount() { return desktopApps.size(); }
        @Override public Object getItem(int position) { return desktopApps.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(MainActivity.this).inflate(R.layout.grid_item, parent, false);
            }
            AppInfo info = desktopApps.get(position);
            ((ImageView) row.findViewById(R.id.grid_icon)).setImageDrawable(info.icon);
            ((TextView) row.findViewById(R.id.grid_name)).setText(info.label);
            return row;
        }
    }

    private class ManageAdapter extends BaseAdapter {
        @Override public int getCount() { return shownApps.size(); }
        @Override public Object getItem(int position) { return shownApps.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(MainActivity.this).inflate(R.layout.item, parent, false);
            }
            AppInfo info = shownApps.get(position);
            ((ImageView) row.findViewById(R.id.icon)).setImageDrawable(info.icon);
            ((TextView) row.findViewById(R.id.name)).setText(info.label);
            ((TextView) row.findViewById(R.id.pkg)).setText(info.pkg);
            CompoundButton sw = (CompoundButton) row.findViewById(R.id.switch_view);
            sw.setClickable(false);
            sw.setFocusable(false);
            // 不注册 OnCheckedChangeListener：setChecked 只是显示状态，点击由整行处理
            sw.setChecked(!hiddenPkgs.contains(info.pkg));
            return row;
        }
    }
}
