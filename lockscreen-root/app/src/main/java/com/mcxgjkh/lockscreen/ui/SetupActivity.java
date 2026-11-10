package com.mcxgjkh.lockscreen.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.mcxgjkh.lockscreen.R;
import com.mcxgjkh.lockscreen.core.CredentialStore;

/**
 * 凭证设置：先输入、再确认，两次一致才落盘（只存 PBKDF2 哈希）。
 * 密码与图案共用一次两步流程，避免两套几乎相同的界面代码。
 */
public class SetupActivity extends Activity implements PatternView.OnPatternListener {

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_PIN = "pin";
    public static final String MODE_PATTERN = "pattern";
    /** 第二密码：只用于进入"只显示时间"界面。 */
    public static final String MODE_CLOCK = "clock";

    private String mode = MODE_PIN;
    private int step = 1;
    private String firstPin;
    private int[] firstPattern;

    private TextView tvTitle;
    private TextView tvHint;
    private TextView tvMsg;
    private EditText etPin;
    private PatternView pvPattern;
    private Button btnNext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getIntent() == null ? MODE_PIN
                : getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) {
            mode = MODE_PIN;
        }
        setContentView(R.layout.activity_setup);

        tvTitle = (TextView) findViewById(R.id.tv_setup_title);
        tvHint = (TextView) findViewById(R.id.tv_setup_hint);
        tvMsg = (TextView) findViewById(R.id.tv_setup_msg);
        etPin = (EditText) findViewById(R.id.et_pin);
        pvPattern = (PatternView) findViewById(R.id.pv_setup);
        btnNext = (Button) findViewById(R.id.btn_next);

        boolean isPin = !MODE_PATTERN.equals(mode);
        if (isClock()) {
            tvTitle.setText(R.string.setup_clock_title);
            tvHint.setText(R.string.setup_clock_hint);
        } else {
            tvTitle.setText(isPin ? R.string.setup_pin_title : R.string.setup_pattern_title);
            tvHint.setText(isPin ? R.string.setup_pin_hint : R.string.setup_pattern_hint);
        }
        etPin.setVisibility(isPin ? View.VISIBLE : View.GONE);
        pvPattern.setVisibility(isPin ? View.GONE : View.VISIBLE);
        if (!isPin) {
            pvPattern.setOnPatternListener(this);
            // 图案是"画完即进入下一步"，不需要确认按钮
            btnNext.setVisibility(View.GONE);
            findViewById(R.id.btn_clear).setVisibility(View.GONE);
        }

        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onNext();
            }
        });
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetInput(true);
            }
        });

        etPin.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                tvMsg.setText("");
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private boolean isClock() {
        return MODE_CLOCK.equals(mode);
    }

    private void onNext() {
        if (!MODE_PATTERN.equals(mode)) {
            handlePinStep();
        } else {
            // 图案模式由 onPatternDetected 推进，这里只提示
            tvMsg.setText(R.string.setup_pattern_hint);
        }
    }

    private void handlePinStep() {
        String input = etPin.getText() == null ? "" : etPin.getText().toString().trim();
        if (step == 1) {
            if (input.length() < CredentialStore.MIN_PIN || input.length() > CredentialStore.MAX_PIN) {
                tvMsg.setText(R.string.setup_pin_hint);
                return;
            }
            if (isClock() && CredentialStore.verifyPin(this, input)) {
                tvMsg.setText(R.string.setup_clock_same);
                return;
            }
            firstPin = input;
            step = 2;
            etPin.setText("");
            tvHint.setText(R.string.setup_repeat);
            btnNext.setText(R.string.confirm);
            return;
        }
        if (firstPin == null) {
            step = 1;
            tvHint.setText(R.string.setup_pin_hint);
            btnNext.setText(R.string.next);
            return;
        }
        if (!firstPin.equals(input)) {
            tvMsg.setText(R.string.setup_mismatch);
            resetInput(true);
            return;
        }
        boolean saved = isClock()
                ? CredentialStore.setClockPin(this, input)
                : CredentialStore.setPin(this, input);
        if (saved) {
            Toast.makeText(this, R.string.setup_done, Toast.LENGTH_SHORT).show();
            finish();
        } else {
            tvMsg.setText(isClock() ? R.string.setup_clock_same : R.string.setup_pin_hint);
            resetInput(true);
        }
    }

    @Override
    public void onPatternDetected(int[] pattern) {
        if (isPatternTooShort(pattern)) {
            tvMsg.setText(R.string.setup_pattern_hint);
            pvPattern.reset();
            return;
        }
        if (step == 1) {
            firstPattern = pattern;
            step = 2;
            pvPattern.reset();
            tvHint.setText(R.string.setup_repeat);
            btnNext.setText(R.string.confirm);
            tvMsg.setText("");
            return;
        }
        if (firstPattern == null) {
            step = 1;
            tvHint.setText(R.string.setup_pattern_hint);
            btnNext.setText(R.string.next);
            pvPattern.reset();
            return;
        }
        if (!same(firstPattern, pattern)) {
            tvMsg.setText(R.string.setup_mismatch);
            resetInput(true);
            return;
        }
        if (CredentialStore.setPattern(this, pattern)) {
            Toast.makeText(this, R.string.setup_done, Toast.LENGTH_SHORT).show();
            finish();
        } else {
            tvMsg.setText(R.string.setup_pattern_hint);
            resetInput(true);
        }
    }

    private boolean isPatternTooShort(int[] pattern) {
        return pattern == null || pattern.length < CredentialStore.MIN_PATTERN;
    }

    private void resetInput(boolean full) {
        etPin.setText("");
        pvPattern.reset();
        tvMsg.setText("");
        if (full) {
            step = 1;
            firstPin = null;
            firstPattern = null;
            if (MODE_PATTERN.equals(mode)) {
                tvHint.setText(R.string.setup_pattern_hint);
            } else if (isClock()) {
                tvHint.setText(R.string.setup_clock_hint);
            } else {
                tvHint.setText(R.string.setup_pin_hint);
            }
            btnNext.setText(R.string.next);
        }
    }

    private static boolean same(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }
}
