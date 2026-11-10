package com.mcxgjkh.otpvault.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.mcxgjkh.otpvault.R;
import com.mcxgjkh.otpvault.data.Account;
import com.mcxgjkh.otpvault.data.Prefs;
import com.mcxgjkh.otpvault.data.VaultStore;
import com.mcxgjkh.otpvault.otp.OtpauthUri;
import com.mcxgjkh.otpvault.util.ExportHelper;
import com.mcxgjkh.otpvault.util.WatchUi;

import com.mcxgjkh.otpvault.util.ImportHelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 账户列表 + 全部入口。设计约束：
 * · 没有 Service、没有 Alarm、没有网络：离开界面就停止刷新，进程可被随时回收；
 * · 列表每秒只刷新可见行（见 AccountAdapter.bindCode），不整表重绑；
 * · 导出走 SAF（ACTION_CREATE_DOCUMENT），因此不需要申请存储权限。
 */
public class MainActivity extends Activity {

    private static final int MENU_ADD = 1;
    private static final int MENU_IMPORT = 2;
    private static final int MENU_EXPORT = 3;
    private static final int MENU_SETTINGS = 4;

    private static final int REQ_EDIT = 100;
    private static final int REQ_PIN = 101;
    private static final int REQ_EXPORT = 102;
    private static final int REQ_IMPORT_FILE = 103;

    private ListView listView;
    private TextView emptyView;
    private AccountAdapter adapter;
    private Prefs prefs;
    private Handler handler;
    private boolean corruptionNotified;
    private boolean locked;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshVisibleRows();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Prefs.get(this);
        WatchUi.applyRoundInset(findViewById(R.id.root), this);
        handler = new Handler(Looper.getMainLooper());

        listView = (ListView) findViewById(R.id.list);
        emptyView = (TextView) findViewById(R.id.empty);
        adapter = new AccountAdapter(this);
        listView.setAdapter(adapter);
        listView.setEmptyView(emptyView);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Account a = adapter.getItem(position);
                Intent i = new Intent(MainActivity.this, DetailActivity.class);
                i.putExtra(DetailActivity.EXTRA_ID, a.id);
                startActivity(i);
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                confirmDelete(adapter.getItem(position));
                return true;
            }
        });

        bindQuickBar();

        if (prefs.hasPin() && !Session.isUnlocked()) {
            locked = true;
            listView.setVisibility(View.INVISIBLE);
            Intent i = new Intent(this, PinActivity.class);
            i.putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY);
            startActivityForResult(i, REQ_PIN);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** 手表没有菜单键：极小屏上直接用一排按钮，功能与菜单完全一致。 */
    private void bindQuickBar() {
        boolean compact = WatchUi.isCompact(this);
        findViewById(R.id.quick_bar).setVisibility(compact ? View.VISIBLE : View.GONE);
        if (!compact) {
            return;
        }
        findViewById(R.id.btn_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, EditActivity.class), REQ_EDIT);
            }
        });
        findViewById(R.id.btn_import).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showImportDialog();
            }
        });
        findViewById(R.id.btn_export).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showExportDialog();
            }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
    }

    // ------------------------------------------------------------ 列表

    private void reload() {
        VaultStore store = VaultStore.get(this);
        List<Account> list = store.all();
        adapter.setItems(list);
        if (store.hadCorruption() && !corruptionNotified) {
            corruptionNotified = true;
            new AlertDialog.Builder(this)
                    .setTitle("数据文件损坏")
                    .setMessage("原有密钥库无法解密（可能被修改或因系统异常损坏）。\n"
                            + "已把原文件改名保留为 vault.dat.corrupt.*，当前列表为空。\n"
                            + "如有备份或导出文件，请重新导入。")
                    .setPositiveButton(R.string.confirm, null)
                    .show();
        }
    }

    private void refreshVisibleRows() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < listView.getChildCount(); i++) {
            View row = listView.getChildAt(i);
            Object tag = row.getTag();
            if (!(tag instanceof Integer)) {
                continue;
            }
            int pos = ((Integer) tag).intValue();
            if (pos < 0 || pos >= adapter.getCount()) {
                continue;
            }
            AccountAdapter.bindCode(row, adapter.getItem(pos), prefs, now);
        }
    }

    private void confirmDelete(final Account a) {
        new AlertDialog.Builder(this)
                .setTitle(a.displayTitle())
                .setMessage(R.string.delete_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (!VaultStore.get(MainActivity.this).delete(a.id)) {
                            Toast.makeText(MainActivity.this, "删除失败", Toast.LENGTH_SHORT).show();
                        }
                        reload();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------ 菜单

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_ADD, 0, R.string.add);
        menu.add(0, MENU_IMPORT, 1, R.string.import_menu);
        menu.add(0, MENU_EXPORT, 2, R.string.export);
        menu.add(0, MENU_SETTINGS, 3, R.string.settings);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_ADD:
                startActivityForResult(new Intent(this, EditActivity.class), REQ_EDIT);
                return true;
            case MENU_IMPORT:
                showImportDialog();
                return true;
            case MENU_EXPORT:
                showExportDialog();
                return true;
            case MENU_SETTINGS:
                showSettings();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    // ------------------------------------------------------------ 导入

    private void showImportDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.import_choose)
                .setItems(new CharSequence[]{
                        getString(R.string.import_from_json),
                        getString(R.string.import_from_uri)
                }, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            startPickJson();
                        } else {
                            showUriDialog();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** ① JSON 文件导入：走系统文件选择器，读不到就提示改用链接。 */
    private void startPickJson() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain"});
        try {
            startActivityForResult(i, REQ_IMPORT_FILE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.import_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    private void importFromFile(Uri uri) {
        InputStream is = null;
        try {
            is = getContentResolver().openInputStream(uri);
            if (is == null) {
                throw new IllegalStateException("no stream");
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 2 * 1024 * 1024) {
                    throw new IllegalStateException("too large");
                }
            }
            String text = new String(bos.toByteArray(), "UTF-8");
            if (ImportHelper.isMigration(text)) {
                Toast.makeText(this, R.string.import_migration, Toast.LENGTH_LONG).show();
                return;
            }
            importAccounts(ImportHelper.parse(text));
        } catch (Exception e) {
            Toast.makeText(this, R.string.import_file_fail, Toast.LENGTH_LONG).show();
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** ② otpauth 链接导入：支持多行批量粘贴。 */
    private void showUriDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(4);
        input.setHint("otpauth://totp/...");
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).coerceToText(this);
                if (text != null && text.toString().contains("otpauth://")) {
                    input.setText(text.toString());
                }
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.paste_title)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        importText(input.getText() == null ? "" : input.getText().toString());
                    }
                })
                .show();
    }

    private void importText(String text) {
        if (ImportHelper.isMigration(text)) {
            Toast.makeText(this, R.string.import_migration, Toast.LENGTH_LONG).show();
            return;
        }
        importAccounts(ImportHelper.parse(text));
    }

    private void importAccounts(List<Account> parsed) {
        if (parsed == null || parsed.isEmpty()) {
            Toast.makeText(this, R.string.import_none, Toast.LENGTH_SHORT).show();
            return;
        }
        int added = VaultStore.get(this).importAll(parsed);
        Toast.makeText(this, getString(R.string.imported, added), Toast.LENGTH_SHORT).show();
        reload();
    }

    // ------------------------------------------------------------ 导出

    /** 一键导出：始终写成一个 JSON 文件（含全部字段与 otpauth 链接）。 */
    private void showExportDialog() {
        final int count = VaultStore.get(this).size();
        if (count == 0) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.export_title)
                .setMessage(getString(R.string.export_json_msg, count) + "\n\n"
                        + getString(R.string.export_warn))
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.export_clipboard, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyToClipboard(ExportHelper.build(VaultStore.get(MainActivity.this).all(),
                                ExportHelper.FMT_JSON));
                    }
                })
                .setPositiveButton(R.string.export_to_file, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startCreateDocument();
                    }
                })
                .show();
    }

    private void startCreateDocument() {
        if (VaultStore.get(this).size() == 0) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(ExportHelper.mime(ExportHelper.FMT_JSON));
        i.putExtra(Intent.EXTRA_TITLE, ExportHelper.fileName(ExportHelper.FMT_JSON));
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            // 手表通常没有 DocumentsUI：退回剪贴板，保证"一键导出"仍可用
            copyToClipboard(ExportHelper.build(VaultStore.get(this).all(),
                    ExportHelper.FMT_JSON));
            Toast.makeText(this, R.string.export_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                writeExport(data.getData());
            }
            return;
        }
        if (requestCode == REQ_IMPORT_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importFromFile(data.getData());
            }
            return;
        }
        if (requestCode == REQ_PIN) {
            if (resultCode != RESULT_OK) {
                finish();
                return;
            }
            if (locked) {
                locked = false;
                listView.setVisibility(View.VISIBLE);
            }
            return;
        }
        if (requestCode == REQ_EDIT) {
            reload();
        }
    }

    private void writeExport(Uri uri) {
        OutputStream os = null;
        try {
            String text = ExportHelper.build(VaultStore.get(this).all(),
                    ExportHelper.FMT_JSON);
            os = getContentResolver().openOutputStream(uri);
            if (os == null) {
                throw new IllegalStateException("cannot open output stream");
            }
            os.write(text.getBytes("UTF-8"));
            os.flush();
            Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.export_fail, Toast.LENGTH_SHORT).show();
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("OtpVault", text));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 设置

    private void showSettings() {
        String pinState = prefs.hasPin() ? getString(R.string.settings_pin_on)
                : getString(R.string.settings_pin_off);
        final String[] items = new String[]{
                getString(R.string.settings_offset, prefs.timeOffset()),
                pinState,
                getString(R.string.shape_title) + "：" + shapeLabel(),
                "关于"
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            showOffsetDialog();
                        } else if (which == 1) {
                            if (prefs.hasPin()) {
                                showDisablePinDialog();
                            } else {
                                Intent i = new Intent(MainActivity.this, PinActivity.class);
                                i.putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_SET);
                                startActivityForResult(i, REQ_PIN);
                            }
                        } else if (which == 2) {
                            showShapeDialog();
                        } else {
                            showAbout();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String shapeLabel() {
        int s = prefs.screenShape();
        if (s == WatchUi.SHAPE_SQUARE) {
            return getString(R.string.shape_square);
        }
        if (s == WatchUi.SHAPE_ROUND) {
            return getString(R.string.shape_round);
        }
        return getString(R.string.shape_auto);
    }

    private void showShapeDialog() {
        final String[] labels = new String[]{
                getString(R.string.shape_auto),
                getString(R.string.shape_square),
                getString(R.string.shape_round)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.shape_title)
                .setSingleChoiceItems(labels, prefs.screenShape(), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.setScreenShape(which);
                        dialog.dismiss();
                        recreate();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showOffsetDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(String.valueOf(prefs.timeOffset()));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        TextView hint = new TextView(this);
        hint.setText(R.string.settings_offset_hint);
        hint.setTextSize(12f);
        box.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String raw = input.getText() == null ? "" : input.getText().toString().trim();
                        if (TextUtils.isEmpty(raw)) {
                            prefs.setTimeOffset(0);
                            return;
                        }
                        try {
                            int v = Integer.parseInt(raw);
                            if (v > 86400) {
                                v = 86400;
                            }
                            if (v < -86400) {
                                v = -86400;
                            }
                            prefs.setTimeOffset(v);
                            reload();
                        } catch (NumberFormatException e) {
                            Toast.makeText(MainActivity.this, R.string.err_number, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void showDisablePinDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        input.setLayoutParams(lp);
        new AlertDialog.Builder(this)
                .setTitle(R.string.pin_disable)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String pin = input.getText() == null ? "" : input.getText().toString();
                        if (prefs.verifyPin(pin)) {
                            prefs.clearPin();
                            Toast.makeText(MainActivity.this, R.string.pin_disabled, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, R.string.pin_wrong, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setMessage(getString(R.string.settings_about)
                        + "\n\n本应用无网络权限，密钥不会离开设备。\n"
                        + "忘记访问 PIN 无法找回（密钥仍加密在本地），只能清除应用数据后重新导入。")
                .setPositiveButton(R.string.confirm, null)
                .show();
    }
}
