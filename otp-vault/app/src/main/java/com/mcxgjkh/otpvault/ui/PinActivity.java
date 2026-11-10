package com.mcxgjkh.otpvault.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.mcxgjkh.otpvault.R;
import com.mcxgjkh.otpvault.data.Prefs;
import com.mcxgjkh.otpvault.util.WatchUi;

/**
 * 应用访问 PIN（可选功能，默认关闭）。
 * 它不是密钥派生的一部分：忘记 PIN 不会导致密钥永久不可用（清除应用数据后仍可用导出文件恢复）。
 */
public class PinActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_VERIFY = "verify";
    public static final String MODE_SET = "set";

    private static final int MIN_LEN = 4;
    private static final int MAX_LEN = 8;

    private String mode = MODE_VERIFY;
    private int step = 1;
    private String first;

    private EditText etPin;
    private TextView tvTitle;
    private TextView tvHint;
    private TextView tvMsg;
    private Button btnOk;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_pin);
        WatchUi.applyRoundInset(findViewById(R.id.root), this);

        prefs = Prefs.get(this);
        mode = getIntent() == null ? MODE_VERIFY : getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) {
            mode = MODE_VERIFY;
        }
        boolean setting = MODE_SET.equals(mode);

        tvTitle = (TextView) findViewById(R.id.tv_title);
        tvHint = (TextView) findViewById(R.id.tv_hint);
        tvMsg = (TextView) findViewById(R.id.tv_msg);
        etPin = (EditText) findViewById(R.id.et_pin);
        btnOk = (Button) findViewById(R.id.btn_ok);

        tvTitle.setText(setting ? R.string.pin_set_title : R.string.pin_title);
        tvHint.setText(R.string.pin_hint);
        if (setting) {
            tvMsg.setText("");
        }
        btnOk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit();
            }
        });
    }

    private void submit() {
        String input = etPin.getText() == null ? "" : etPin.getText().toString();
        if (!MODE_SET.equals(mode)) {
            if (prefs.verifyPin(input)) {
                Session.unlock();
                setResult(RESULT_OK);
                finish();
            } else {
                tvMsg.setText(R.string.pin_wrong);
                etPin.setText("");
            }
            return;
        }
        if (step == 1) {
            if (input.length() < MIN_LEN || input.length() > MAX_LEN) {
                tvMsg.setText(R.string.pin_hint);
                return;
            }
            first = input;
            step = 2;
            etPin.setText("");
            tvHint.setText("请再次输入以确认");
            btnOk.setText(R.string.confirm);
            tvMsg.setText("");
            return;
        }
        if (first == null || !first.equals(input)) {
            step = 1;
            first = null;
            etPin.setText("");
            tvHint.setText(R.string.pin_hint);
            btnOk.setText(R.string.confirm);
            tvMsg.setText("两次输入不一致");
            return;
        }
        if (prefs.setPin(input)) {
            Session.unlock();
            Toast.makeText(this, R.string.pin_set_done, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } else {
            tvMsg.setText(R.string.pin_hint);
        }
    }
}
