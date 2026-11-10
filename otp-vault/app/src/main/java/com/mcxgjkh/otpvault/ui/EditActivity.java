package com.mcxgjkh.otpvault.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.mcxgjkh.otpvault.R;
import com.mcxgjkh.otpvault.data.Account;
import com.mcxgjkh.otpvault.data.VaultStore;
import com.mcxgjkh.otpvault.otp.Base32;
import com.mcxgjkh.otpvault.otp.Otp;
import com.mcxgjkh.otpvault.otp.OtpauthUri;
import com.mcxgjkh.otpvault.util.WatchUi;

/**
 * 新增/编辑账户。支持手动录入，也支持从剪贴板的 otpauth:// 链接一键填充
 * （不引入摄像头与二维码库 —— 扫码交给系统相机/其他应用，本应用只做密钥管理）。
 */
public class EditActivity extends Activity {

    public static final String EXTRA_ID = "id";

    private EditText etIssuer;
    private EditText etAccount;
    private EditText etSecret;
    private EditText etPeriod;
    private EditText etCounter;
    private Spinner spType;
    private Spinner spAlgo;
    private Spinner spDigits;
    private View rowPeriod;
    private View rowCounter;
    private TextView tvMsg;

    private String editingId;
    private Account editing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_edit);
        WatchUi.applyRoundInset(findViewById(R.id.root), this);

        etIssuer = (EditText) findViewById(R.id.et_issuer);
        etAccount = (EditText) findViewById(R.id.et_account);
        etSecret = (EditText) findViewById(R.id.et_secret);
        etPeriod = (EditText) findViewById(R.id.et_period);
        etCounter = (EditText) findViewById(R.id.et_counter);
        spType = (Spinner) findViewById(R.id.sp_type);
        spAlgo = (Spinner) findViewById(R.id.sp_algo);
        spDigits = (Spinner) findViewById(R.id.sp_digits);
        rowPeriod = findViewById(R.id.row_period);
        rowCounter = findViewById(R.id.row_counter);
        tvMsg = (TextView) findViewById(R.id.tv_msg);

        setupSpinner(spType, R.array.type_labels);
        setupSpinner(spAlgo, R.array.algo_labels);
        setupSpinner(spDigits, R.array.digits_labels);

        spType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean hotp = position == 1;
                rowPeriod.setVisibility(hotp ? View.GONE : View.VISIBLE);
                rowCounter.setVisibility(hotp ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        findViewById(R.id.btn_paste_uri).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteFromClipboard();
            }
        });
        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });

        Intent intent = getIntent();
        editingId = intent == null ? null : intent.getStringExtra(EXTRA_ID);
        if (editingId != null) {
            editing = VaultStore.get(this).find(editingId);
            if (editing != null) {
                fill(editing);
            }
        }
        if (editing == null) {
            etPeriod.setText("30");
            etCounter.setText("0");
        }
    }

    private void setupSpinner(Spinner sp, int arrayRes) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, arrayRes,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
    }

    private void fill(Account a) {
        etIssuer.setText(a.issuer);
        etAccount.setText(a.account);
        etSecret.setText(Base32.group(a.secret));
        spType.setSelection(a.isHotp() ? 1 : 0);
        spAlgo.setSelection(algoIndex(a.algorithm));
        spDigits.setSelection(a.digits == 8 ? 1 : 0);
        etPeriod.setText(String.valueOf(a.period > 0 ? a.period : 30));
        etCounter.setText(String.valueOf(a.counter));
    }

    private static int algoIndex(String algorithm) {
        String a = Otp.normalizeAlgorithm(algorithm);
        if (Otp.SHA256.equals(a)) {
            return 1;
        }
        if (Otp.SHA512.equals(a)) {
            return 2;
        }
        return 0;
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            Toast.makeText(this, R.string.import_none, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text == null) {
            return;
        }
        for (String token : text.toString().split("\\s+")) {
            Account a = OtpauthUri.parse(token);
            if (a != null) {
                fill(a);
                Toast.makeText(this, R.string.filled, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Toast.makeText(this, R.string.import_none, Toast.LENGTH_SHORT).show();
    }

    private void save() {
        String issuer = text(etIssuer).trim();
        String accountName = text(etAccount).trim();
        String secretRaw = text(etSecret);
        if (accountName.length() == 0) {
            tvMsg.setText(R.string.err_account);
            return;
        }
        if (!Base32.isValid(secretRaw)) {
            tvMsg.setText(R.string.err_secret);
            return;
        }
        int period = (int) parseLong(text(etPeriod), 30L);
        if (period <= 0) {
            period = 30;
        }
        long counter = parseLong(text(etCounter), 0L);
        if (counter < 0L) {
            counter = 0L;
        }

        Account a = editing != null ? editing.copy() : new Account();
        a.issuer = issuer;
        a.account = accountName;
        a.secret = Base32.normalize(secretRaw);
        a.type = spType.getSelectedItemPosition() == 1 ? "HOTP" : "TOTP";
        a.algorithm = Otp.normalizeAlgorithm(String.valueOf(spAlgo.getSelectedItem()));
        a.digits = spDigits.getSelectedItemPosition() == 1 ? 8 : 6;
        a.period = period;
        a.counter = counter;

        if (!VaultStore.get(this).upsert(a)) {
            Toast.makeText(this, "保存失败，请检查存储空间", Toast.LENGTH_LONG).show();
            return;
        }
        setResult(RESULT_OK);
        finish();
    }

    private static String text(EditText et) {
        return et.getText() == null ? "" : et.getText().toString();
    }

    private static long parseLong(String raw, long def) {
        if (raw == null || raw.trim().length() == 0) {
            return def;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
