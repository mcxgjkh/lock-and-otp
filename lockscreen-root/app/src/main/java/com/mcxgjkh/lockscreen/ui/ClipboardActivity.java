package com.mcxgjkh.lockscreen.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.mcxgjkh.lockscreen.R;
import com.mcxgjkh.lockscreen.core.Clips;
import com.mcxgjkh.lockscreen.core.Prefs;
import com.mcxgjkh.lockscreen.core.WatchUi;

import java.util.ArrayList;
import java.util.List;

/**
 * 自带剪贴板：系统没有复制/粘贴 UI 时，用它来管理文本。
 * · 上方显示"当前内容"（本应用存储，Android 10+ 后台读不到系统剪贴板，只能以自己为准）；
 * · 中间可编辑，点「复制到剪贴板」会同时写入系统剪贴板；
 * · 下面是历史，点条目装载到输入框，长按删除；
 * · 真正的"粘贴到别的应用"在悬浮球面板的「粘贴」按钮里（root 注入）。
 */
public class ClipboardActivity extends Activity {

    private TextView tvCurrent;
    private TextView tvStatus;
    private EditText etText;
    private ArrayAdapter<String> adapter;
    private final List<String> items = new ArrayList<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clipboard);
        WatchUi.applyRoundInset(findViewById(R.id.root), this);

        tvStatus = (TextView) findViewById(R.id.tv_status);
        tvCurrent = (TextView) findViewById(R.id.tv_current);
        etText = (EditText) findViewById(R.id.et_text);
        ListView listView = (ListView) findViewById(R.id.list);

        adapter = new ArrayAdapter<String>(this, R.layout.item_clip, items);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                etText.setText(items.get(position));
                if (etText.getText() != null) {
                    etText.setSelection(etText.getText().length());
                }
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Clips.removeHistory(ClipboardActivity.this, items.get(position));
                refresh();
                return true;
            }
        });

        Button set = (Button) findViewById(R.id.btn_set);
        set.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String text = etText.getText() == null ? "" : etText.getText().toString();
                if (text.length() == 0) {
                    return;
                }
                // 写系统剪贴板失败时会退回 root 通道，可能耗时，放工作线程
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final boolean sysOk = Clips.setCurrent(ClipboardActivity.this, text);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing()) {
                                    return;
                                }
                                Toast.makeText(ClipboardActivity.this,
                                        sysOk ? R.string.clip_copied : R.string.clip_saved_local,
                                        Toast.LENGTH_SHORT).show();
                                refresh();
                            }
                        });
                    }
                }, "clip-set").start();
            }
        });

        findViewById(R.id.btn_read).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Clips.syncFromSystem(ClipboardActivity.this)) {
                    refresh();
                    Toast.makeText(ClipboardActivity.this, R.string.clip_copied,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                String sys = Clips.readSystem(ClipboardActivity.this);
                if (sys.length() == 0) {
                    Toast.makeText(ClipboardActivity.this, R.string.clip_read_none,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                etText.setText(sys);
            }
        });

        // 普通通道读不到时（ROM 只允许焦点应用读取），用 system 身份再读一次
        findViewById(R.id.btn_root).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(ClipboardActivity.this, R.string.clip_refresh_root,
                        Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final boolean got = Clips.syncFromRoot(ClipboardActivity.this);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing()) {
                                    return;
                                }
                                refresh();
                                if (!got) {
                                    Toast.makeText(ClipboardActivity.this,
                                            R.string.clip_read_none, Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                }, "clip-root").start();
            }
        });

        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Clips.clearHistory(ClipboardActivity.this);
                Toast.makeText(ClipboardActivity.this, R.string.clip_cleared,
                        Toast.LENGTH_SHORT).show();
                refresh();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 界面在前台时读剪贴板不受限制：把最近一次复制的内容拉进来，
        // 这样即使服务被系统清掉，打开本页也能立刻看到刚刚复制的东西。
        Clips.syncFromSystem(this);
        refresh();
    }

    private void refresh() {
        boolean on = Prefs.get(this).clipMonitor();
        String label = getString(on ? R.string.clip_status_on : R.string.clip_status_off);
        tvStatus.setText(getString(R.string.clip_status_stats, label,
                Clips.captureCount(this)));
        tvStatus.setTextColor(on ? 0xFF2E7D32 : 0xFFC62828);
        String cur = Clips.current(this);
        tvCurrent.setText(cur.length() == 0 ? getString(R.string.clip_empty) : cur);
        items.clear();
        items.addAll(Clips.history(this));
        adapter.notifyDataSetChanged();
    }
}
