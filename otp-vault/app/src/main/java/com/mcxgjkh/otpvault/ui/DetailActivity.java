package com.mcxgjkh.otpvault.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.graphics.Typeface;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.mcxgjkh.otpvault.R;
import com.mcxgjkh.otpvault.data.Account;
import com.mcxgjkh.otpvault.data.Prefs;
import com.mcxgjkh.otpvault.data.VaultStore;
import com.mcxgjkh.otpvault.otp.Base32;
import com.mcxgjkh.otpvault.otp.Otp;
import com.mcxgjkh.otpvault.otp.OtpauthUri;
import com.mcxgjkh.otpvault.util.ClipBridge;
import com.mcxgjkh.otpvault.util.WatchUi;

/**
 * 账户详情：大号验证码 + 倒计时 + **原始密钥查看/复制** + otpauth 链接 + 编辑/删除。
 * 密钥默认打码，必须点"显示密钥"才呈现 —— 避免随手一瞥泄露；
 * 本页开启 FLAG_SECURE，禁止截屏/录屏。
 */
public class DetailActivity extends Activity {

    public static final String EXTRA_ID = "id";
    private static final int REQ_EDIT = 200;

    private TextView tvIssuer;
    private TextView tvAccount;
    private TextView tvCode;
    private TextView tvRemain;
    private TextView tvSecret;
    private TextView tvUri;
    private TextView tvMeta;
    private ProgressBar pb;
    private Button btnReveal;
    private Button btnNext;

    private Account account;
    private boolean revealed;
    private Handler handler;
    private String lastCopied;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateCode();
            handler.postDelayed(this, 1000L);
        }
    };

    private final Runnable clearClipboard = new Runnable() {
        @Override
        public void run() {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || lastCopied == null) {
                return;
            }
            ClipData cur = cm.getPrimaryClip();
            if (cur == null || cur.getItemCount() == 0) {
                return;
            }
            CharSequence text = cur.getItemAt(0).coerceToText(DetailActivity.this);
            if (text != null && lastCopied.contentEquals(text)) {
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
                lastCopied = null;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_detail);
        WatchUi.applyRoundInset(findViewById(R.id.root), this);
        handler = new Handler(Looper.getMainLooper());

        tvIssuer = (TextView) findViewById(R.id.tv_issuer);
        tvAccount = (TextView) findViewById(R.id.tv_account);
        tvCode = (TextView) findViewById(R.id.tv_code);
        tvRemain = (TextView) findViewById(R.id.tv_remain);
        tvSecret = (TextView) findViewById(R.id.tv_secret);
        tvUri = (TextView) findViewById(R.id.tv_uri);
        tvMeta = (TextView) findViewById(R.id.tv_meta);
        pb = (ProgressBar) findViewById(R.id.pb);
        btnReveal = (Button) findViewById(R.id.btn_reveal);
        btnNext = (Button) findViewById(R.id.btn_next);

        btnReveal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                revealed = !revealed;
                renderSecret();
            }
        });
        findViewById(R.id.btn_copy_secret).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (account != null) {
                    copy(account.secret);
                }
            }
        });
        findViewById(R.id.btn_algo_probe).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAlgoProbe();
            }
        });
        findViewById(R.id.btn_copy_code).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (account != null) {
                    copy(currentCode());
                }
            }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (account == null) {
                    return;
                }
                VaultStore.get(DetailActivity.this).bumpCounter(account.id);
                account = VaultStore.get(DetailActivity.this).find(account.id);
                updateCode();
            }
        });
        findViewById(R.id.btn_edit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (account == null) {
                    return;
                }
                Intent i = new Intent(DetailActivity.this, EditActivity.class);
                i.putExtra(EditActivity.EXTRA_ID, account.id);
                startActivityForResult(i, REQ_EDIT);
            }
        });
        findViewById(R.id.btn_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EDIT) {
            load();
        }
    }

    private void load() {
        Intent intent = getIntent();
        String id = intent == null ? null : intent.getStringExtra(EXTRA_ID);
        account = VaultStore.get(this).find(id);
        if (account == null) {
            Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        tvIssuer.setText(account.displayTitle());
        tvAccount.setText(account.account);
        tvUri.setText(OtpauthUri.build(account));
        String extra = account.isHotp()
                ? getString(R.string.meta_hotp_counter, account.counter)
                : getString(R.string.meta_totp_period, account.period > 0 ? account.period : 30);
        tvMeta.setText(getString(R.string.meta_format,
                account.isHotp() ? "HOTP" : "TOTP",
                Otp.normalizeAlgorithm(account.algorithm),
                account.digits, extra));
        btnNext.setVisibility(account.isHotp() ? View.VISIBLE : View.GONE);
        renderSecret();
        updateCode();
    }

    private void renderSecret() {
        if (account == null) {
            return;
        }
        if (revealed) {
            tvSecret.setText(Base32.group(account.secret));
            btnReveal.setText(R.string.hide_secret);
        } else {
            tvSecret.setText(R.string.secret_hidden);
            btnReveal.setText(R.string.show_secret);
        }
    }

    private void updateCode() {
        if (account == null) {
            return;
        }
        Prefs prefs = Prefs.get(this);
        long now = System.currentTimeMillis();
        byte[] key = Base32.decode(account.secret);
        if (account.isHotp()) {
            tvCode.setText(Otp.format(Otp.generate(key, account.counter, account.digits, account.algorithm)));
            pb.setVisibility(View.GONE);
            tvRemain.setText("HOTP · #" + account.counter);
            return;
        }
        int offset = prefs.timeOffset();
        long counter = Otp.totpCounter(now, account.period, offset);
        int remain = Otp.remainingSeconds(now, account.period, offset);
        tvCode.setText(Otp.format(Otp.generate(key, counter, account.digits, account.algorithm)));
        pb.setVisibility(View.VISIBLE);
        pb.setMax(account.period > 0 ? account.period : 30);
        pb.setProgress(remain);
        tvRemain.setText(remain + "s");
    }

    /**
     * 同一时间步用 SHA1 / SHA256 / SHA512 各算一次，让用户和服务端比对。
     * 密钥本身不含算法信息，6~8 位截断也看不出哈希类型，这是唯一可操作的判定方式。
     */
    private void showAlgoProbe() {
        if (account == null) {
            return;
        }
        Prefs prefs = Prefs.get(this);
        long now = System.currentTimeMillis();
        byte[] key = Base32.decode(account.secret);
        long counter = account.isHotp() ? account.counter
                : Otp.totpCounter(now, account.period, prefs.timeOffset());
        String current = Otp.normalizeAlgorithm(account.algorithm);

        StringBuilder sb = new StringBuilder();
        String[] algs = new String[]{Otp.SHA1, Otp.SHA256, Otp.SHA512};
        for (String alg : algs) {
            sb.append(alg).append("   ")
                    .append(Otp.format(Otp.generate(key, counter, account.digits, alg)));
            if (alg.equals(current)) {
                sb.append("   ← ").append(getString(R.string.algo_current));
            }
            sb.append('\n');
        }
        if (!account.isHotp()) {
            sb.append('\n').append(getString(R.string.algo_probe_remain,
                    Otp.remainingSeconds(now, account.period, prefs.timeOffset())));
        }

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        TextView codes = new TextView(this);
        codes.setTypeface(Typeface.MONOSPACE);
        codes.setTextSize(16f);
        codes.setText(sb.toString().trim());

        TextView hint = new TextView(this);
        hint.setText(R.string.algo_probe_hint);
        hint.setTextSize(12f);
        hint.setPadding(0, (int) (12 * density), 0, 0);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(codes);
        box.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle(R.string.algo_probe_title)
                .setView(box)
                .setNeutralButton(R.string.edit, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (account != null) {
                            Intent i = new Intent(DetailActivity.this, EditActivity.class);
                            i.putExtra(EditActivity.EXTRA_ID, account.id);
                            startActivityForResult(i, REQ_EDIT);
                        }
                    }
                })
                .setPositiveButton(R.string.confirm, null)
                .show();
    }

    private String currentCode() {
        if (account == null) {
            return "";
        }
        byte[] key = Base32.decode(account.secret);
        long counter = account.isHotp() ? account.counter
                : Otp.totpCounter(System.currentTimeMillis(), account.period, Prefs.get(this).timeOffset());
        return Otp.generate(key, counter, account.digits, account.algorithm);
    }

    /**
     * 写剪贴板并**读回校验**：部分精简 ROM 的剪贴板服务是空的，
     * setPrimaryClip 不抛异常但什么都没写进去，必须校验才能给出真实反馈。
     */
    private void copy(String text) {
        if (text == null || text.length() == 0) {
            return;
        }
        String err = "";
        boolean ok = false;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                err = "ClipboardManager=null";
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("OtpVault", text));
                ClipData back = cm.getPrimaryClip();
                if (back == null || back.getItemCount() == 0) {
                    err = "读回为空";
                } else {
                    CharSequence cs = back.getItemAt(0).coerceToText(this);
                    ok = cs != null && text.contentEquals(cs.toString());
                    if (!ok) {
                        err = "内容不一致";
                    }
                }
            }
        } catch (Throwable t) {
            err = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        if (!ok) {
            // 系统剪贴板不可用：交给「锁屏」应用，由它的悬浮球注入
            if (ClipBridge.push(this, text)) {
                Toast.makeText(this, R.string.copied_to_lock, Toast.LENGTH_LONG).show();
                return;
            }
            showCopyFallback(text, err);
            return;
        }
        lastCopied = text;
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        handler.removeCallbacks(clearClipboard);
        // 30 秒后自动清空剪贴板，降低被其他应用读取的窗口期
        handler.postDelayed(clearClipboard, 30000L);
    }

    /** 复制失败时的兜底：把内容显示成可选中的文本，至少能看、能手抄。 */
    private void showCopyFallback(String text, String err) {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(22f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(pad, pad / 2, pad, 0);

        new AlertDialog.Builder(this)
                .setTitle(R.string.copy_failed_title)
                .setMessage(getString(R.string.copy_failed_msg, err))
                .setView(tv)
                .setPositiveButton(R.string.confirm, null)
                .show();
    }

    private void confirmDelete() {
        if (account == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(account.displayTitle())
                .setMessage(R.string.delete_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        VaultStore.get(DetailActivity.this).delete(account.id);
                        finish();
                    }
                })
                .show();
    }
}
