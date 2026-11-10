package com.mcxgjkh.lockscreen.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.mcxgjkh.lockscreen.R;
import com.mcxgjkh.lockscreen.core.AccessibilityTool;
import com.mcxgjkh.lockscreen.core.WatchUi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 无障碍服务管理页：列出本机所有可能作为无障碍服务的组件并可直接开关。
 *
 * 默认只显示"声明了无障碍 intent-filter / 系统已识别"的服务；
 * 勾选「显示全部应用的服务」后连未声明权限的服务也列出来（用于排查
 * "某个无障碍应用在系统列表里看不见"的情况），并对未声明权限的组件给出明确告警。
 */
public class AccessibilityActivity extends Activity {

    private ListView listView;
    private TextView emptyView;
    private TextView countView;
    private CheckBox cbAll;
    private Adapter adapter;
    private final List<AccessibilityTool.Item> items = new ArrayList<AccessibilityTool.Item>();
    private final Set<String> enabled = new HashSet<String>();
    private final Set<String> systemEnabled = new HashSet<String>();
    private boolean showAll;
    private volatile boolean applying;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accessibility);
        WatchUi.applyRoundInset(findViewById(R.id.root), this);

        listView = (ListView) findViewById(R.id.list);
        emptyView = (TextView) findViewById(R.id.empty);
        countView = (TextView) findViewById(R.id.tv_count);
        cbAll = (CheckBox) findViewById(R.id.cb_all);
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.setEmptyView(emptyView);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onRowClick(items.get(position));
            }
        });

        cbAll.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                showAll = isChecked;
                reload();
                if (isChecked) {
                    Toast.makeText(AccessibilityActivity.this, R.string.acc_all_tip,
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        findViewById(R.id.btn_sys).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSystemSettings();
            }
        });

        findViewById(R.id.btn_manual).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showManualDialog();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        items.clear();
        items.addAll(AccessibilityTool.list(this, showAll));
        enabled.clear();
        enabled.addAll(AccessibilityTool.enabledSet(this));
        systemEnabled.clear();
        systemEnabled.addAll(AccessibilityTool.systemEnabledSet(this));
        adapter.notifyDataSetChanged();
        int on = 0;
        for (AccessibilityTool.Item it : items) {
            if (enabled.contains(it.flatten)) {
                on++;
            }
        }
        countView.setText(getString(R.string.acc_count, items.size(), on));
        emptyView.setText(R.string.acc_empty);
    }

    private void onRowClick(final AccessibilityTool.Item item) {
        if (applying) {
            return;
        }
        final boolean currentlyEnabled = enabled.contains(item.flatten);
        if (currentlyEnabled) {
            apply(item, false);
            return;
        }
        if (!item.declared) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.acc_no_perm_title)
                    .setMessage(getString(R.string.acc_no_perm_msg, item.label))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.acc_try_anyway,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    apply(item, true);
                                }
                            })
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(item.label)
                .setMessage(getString(R.string.acc_enable_confirm, item.label))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        apply(item, true);
                    }
                })
                .show();
    }

    private void apply(final AccessibilityTool.Item item, final boolean target) {
        applying = true;
        Toast.makeText(this, target ? R.string.acc_state_on : R.string.acc_state_off,
                Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final AccessibilityTool.Result result = AccessibilityTool.setEnabled(
                        getApplicationContext(), item, target);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        applying = false;
                        if (!result.writeOk) {
                            Toast.makeText(AccessibilityActivity.this,
                                    R.string.acc_apply_failed, Toast.LENGTH_LONG).show();
                        } else if (!result.accepted) {
                            new AlertDialog.Builder(AccessibilityActivity.this)
                                    .setTitle(R.string.acc_not_accepted_title)
                                    .setMessage(getString(R.string.acc_not_accepted_msg,
                                            result.detail))
                                    .setPositiveButton(R.string.confirm, null)
                                    .show();
                        }
                        // 系统写入后需要一点时间刷新缓存
                        listView.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!isFinishing()) {
                                    reload();
                                }
                            }
                        }, 350L);
                    }
                });
            }
        }, "acc-toggle").start();
    }

    private void showManualDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.acc_manual_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.acc_manual)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String text = input.getText() == null ? "" : input.getText().toString();
                        AccessibilityTool.Item item =
                                AccessibilityTool.fromComponent(AccessibilityActivity.this, text);
                        if (item == null) {
                            Toast.makeText(AccessibilityActivity.this, R.string.acc_manual_bad,
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        apply(item, true);
                    }
                })
                .show();
    }

    private void openSystemSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.acc_no_settings, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, R.string.acc_no_settings, Toast.LENGTH_LONG).show();
        }
    }

    private class Adapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(AccessibilityActivity.this);

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = inflater.inflate(R.layout.item_accessibility, parent, false);
            }
            AccessibilityTool.Item item = items.get(position);
            TextView label = (TextView) row.findViewById(R.id.tv_label);
            TextView pkg = (TextView) row.findViewById(R.id.tv_pkg);
            CheckBox cb = (CheckBox) row.findViewById(R.id.cb);
            label.setText(item.label);
            StringBuilder sub = new StringBuilder(item.pkg);
            boolean rawOn = enabled.contains(item.flatten);
            boolean realOn = systemEnabled.contains(item.flatten);
            if (rawOn && !realOn) {
                sub.append(" · ").append(getString(R.string.acc_pending));
            } else if (!item.declared) {
                sub.append(" · 未声明无障碍权限");
            } else if (!item.recognized) {
                sub.append(" · 系统未识别");
            }
            pkg.setText(sub.toString());
            cb.setChecked(enabled.contains(item.flatten));
            return row;
        }
    }
}
