package com.mcxgjkh.otpvault.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.mcxgjkh.otpvault.R;
import com.mcxgjkh.otpvault.util.WatchUi;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 内置文件选择器：给没有 DocumentsUI 的手表/精简 ROM 用。
 *
 * 读写范围限定在本应用的外部私有目录（/sdcard/Android/data/<pkg>/files），
 * 因此**不需要任何存储权限**；文件可以用 adb pull 或文件管理器取走。
 */
public class FilePickerActivity extends Activity {

    public static final int MODE_SAVE = 1;
    public static final int MODE_LOAD = 2;

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_PATH = "path";

    private boolean saveMode;
    private File dir;
    private EditText etName;
    private TextView tvPath;
    private ArrayAdapter<String> adapter;
    private final List<File> entries = new ArrayList<File>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_picker);
        WatchUi.applyRoundInset(findViewById(R.id.root), this);

        saveMode = getIntent() == null || getIntent().getIntExtra(EXTRA_MODE, MODE_SAVE) == MODE_SAVE;
        File base = getExternalFilesDir(null);
        if (base == null) {
            base = getFilesDir();
        }
        if (!base.exists()) {
            //noinspection ResultOfMethodCallIgnored
            base.mkdirs();
        }
        dir = base;

        tvPath = (TextView) findViewById(R.id.tv_path);
        etName = (EditText) findViewById(R.id.et_name);
        Button ok = (Button) findViewById(R.id.btn_ok);
        ListView listView = (ListView) findViewById(R.id.list);

        if (saveMode) {
            ok.setText(R.string.fp_save);
            String suggest = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_NAME);
            etName.setText(suggest == null ? "" : suggest);
            etName.setVisibility(View.VISIBLE);
        } else {
            ok.setText(R.string.fp_open);
            etName.setVisibility(View.GONE);
        }

        adapter = new ArrayAdapter<String>(this, R.layout.item_file, new ArrayList<String>());
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                File f = entries.get(position);
                if (f.isDirectory()) {
                    dir = f;
                    refresh();
                } else if (saveMode) {
                    etName.setText(f.getName());
                } else {
                    finishWith(f);
                }
            }
        });

        findViewById(R.id.btn_up).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File parent = dir.getParentFile();
                if (parent != null && parent.getAbsolutePath()
                        .startsWith(baseDir().getAbsolutePath())) {
                    dir = parent;
                    refresh();
                } else {
                    Toast.makeText(FilePickerActivity.this, R.string.fp_top,
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        findViewById(R.id.btn_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (saveMode) {
                    save();
                } else {
                    Toast.makeText(FilePickerActivity.this, R.string.fp_pick_file,
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        refresh();
    }

    private File baseDir() {
        File base = getExternalFilesDir(null);
        return base == null ? getFilesDir() : base;
    }

    private void refresh() {
        tvPath.setText(dir.getAbsolutePath());
        entries.clear();
        List<String> names = new ArrayList<String>();
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File f : files) {
                if (f.isDirectory()) {
                    entries.add(f);
                    names.add("[DIR] " + f.getName());
                } else if (saveMode || isTextLike(f.getName())) {
                    entries.add(f);
                    names.add(f.getName() + "   " + (f.length() / 1024) + " KB");
                }
            }
        }
        adapter.clear();
        adapter.addAll(names);
        adapter.notifyDataSetChanged();
    }

    private void save() {
        String name = etName.getText() == null ? "" : etName.getText().toString().trim();
        if (name.length() == 0) {
            Toast.makeText(this, R.string.fp_name_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        // 只允许单层文件名，防止拼出 ../ 写到目录外
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
            Toast.makeText(this, R.string.fp_name_bad, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!name.toLowerCase().endsWith(".json")) {
            name = name + ".json";
        }
        finishWith(new File(dir, name));
    }

    private void finishWith(File f) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_PATH, f.getAbsolutePath()));
        finish();
    }

    private static boolean isTextLike(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".json") || n.endsWith(".txt") || n.endsWith(".csv");
    }
}
