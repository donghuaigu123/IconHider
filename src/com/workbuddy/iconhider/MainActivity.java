package com.workbuddy.iconhider;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private PackageManager pm;
    private final List<AppInfo> allApps = new ArrayList<AppInfo>();
    private final List<AppInfo> shownApps = new ArrayList<AppInfo>();
    private AppAdapter adapter;
    private EditText searchBox;
    private TextView statusView;
    private String keyword = "";
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static class AppInfo {
        String pkg;
        String activity;
        String label;
        String labelLower;
        Drawable icon;
        boolean visible;
        SwitchHolder holder;
    }

    private static class SwitchHolder {
        boolean muted; // 防止程序化 setChecked 触发回调
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        pm = getPackageManager();

        ListView list = (ListView) findViewById(R.id.list);
        statusView = (TextView) findViewById(R.id.status);
        searchBox = (EditText) findViewById(R.id.search);
        adapter = new AppAdapter();
        list.setAdapter(adapter);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                keyword = s.toString().trim().toLowerCase(Locale.ROOT);
                refreshShown();
            }
        });

        statusView.setText(getString(R.string.loading));
        loadAppsAsync();
    }

    private void loadAppsAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                Intent main = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = pm.queryIntentActivities(
                        main, PackageManager.MATCH_DISABLED_COMPONENTS);

                String self = getPackageName();
                Set<String> seen = new HashSet<String>();
                List<AppInfo> result = new ArrayList<AppInfo>();

                for (ResolveInfo ri : ris) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || self.equals(pkg)) continue;
                    if (!seen.add(pkg)) continue; // 每个应用只保留一个入口，避免重复

                    AppInfo info = new AppInfo();
                    info.pkg = pkg;
                    info.activity = ri.activityInfo.name;
                    CharSequence label = ri.loadLabel(pm);
                    info.label = label != null ? label.toString() : pkg;
                    info.labelLower = info.label.toLowerCase(Locale.ROOT);
                    info.icon = ri.loadIcon(pm);
                    ComponentName cn = new ComponentName(pkg, info.activity);
                    int state = pm.getComponentEnabledSetting(cn);
                    info.visible = state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
                    result.add(info);
                }

                Collections.sort(result, new java.util.Comparator<AppInfo>() {
                    @Override public int compare(AppInfo a, AppInfo b) {
                        boolean ha = a.visible, hb = b.visible;
                        if (ha != hb) return ha ? -1 : 1; // 可见的排前面
                        return a.labelLower.compareTo(b.labelLower);
                    }
                });

                ui.post(new Runnable() {
                    @Override public void run() {
                        allApps.clear();
                        allApps.addAll(result);
                        refreshShown();
                    }
                });
            }
        }, "app-loader").start();
    }

    private void refreshShown() {
        shownApps.clear();
        for (AppInfo a : allApps) {
            if (keyword.isEmpty()
                    || a.labelLower.contains(keyword)
                    || a.pkg.toLowerCase(Locale.ROOT).contains(keyword)) {
                shownApps.add(a);
            }
        }
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    private void updateStatus() {
        int hidden = 0;
        for (AppInfo a : allApps) if (!a.visible) hidden++;
        statusView.setText(getString(R.string.status_format, allApps.size(), hidden));
    }

    private void toggleApp(final AppInfo info, final boolean toVisible) {
        ComponentName cn = new ComponentName(info.pkg, info.activity);
        try {
            pm.setComponentEnabledSetting(cn,
                    toVisible ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                              : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            info.visible = toVisible;
            Toast.makeText(this,
                    getString(toVisible ? R.string.toast_shown : R.string.toast_hidden, info.label),
                    Toast.LENGTH_SHORT).show();
            updateStatus();
            // 重新排序：可见的在前
            Collections.sort(allApps, new java.util.Comparator<AppInfo>() {
                @Override public int compare(AppInfo a, AppInfo b) {
                    if (a.visible != b.visible) return a.visible ? -1 : 1;
                    return a.labelLower.compareTo(b.labelLower);
                }
            });
            refreshShown();
        } catch (Exception e) {
            Toast.makeText(this,
                    "操作失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shownApps.size(); }
        @Override public Object getItem(int position) { return shownApps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item, parent, false);
            }
            final AppInfo info = shownApps.get(position);

            ImageView icon = (ImageView) row.findViewById(R.id.icon);
            TextView name = (TextView) row.findViewById(R.id.name);
            TextView pkg = (TextView) row.findViewById(R.id.pkg);
            CompoundButton sw = (CompoundButton) row.findViewById(R.id.switch_view);

            icon.setImageDrawable(info.icon);
            name.setText(info.label);
            pkg.setText(info.pkg);
            sw.setChecked(info.visible);

            // 程序化 setChecked 之后的监听只在用户点击时生效：
            // 用 holder 标记 + 状态比对，避免复用导致的错乱
            SwitchHolder holder = info.holder;
            if (holder == null) {
                holder = new SwitchHolder();
                info.holder = holder;
            }
            holder.muted = true;
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton btn, boolean checked) {
                    if (btn.isPressed()) { // 只有真实用户点击才触发
                        toggleApp(info, checked);
                    }
                }
            });
            holder.muted = false;
            return row;
        }
    }
}
