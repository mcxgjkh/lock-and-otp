package com.mcxgjkh.otpvault.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.mcxgjkh.otpvault.R;
import com.mcxgjkh.otpvault.data.Account;
import com.mcxgjkh.otpvault.data.Prefs;
import com.mcxgjkh.otpvault.otp.Base32;
import com.mcxgjkh.otpvault.otp.Otp;

import java.util.ArrayList;
import java.util.List;

/**
 * 列表适配器。不使用 RecyclerView（需要 support 库，会显著增大体积）。
 * 每秒刷新时**不调用 notifyDataSetChanged**，只改可见行上的两个控件，
 * 避免整表重绑与无谓的 View 创建。
 */
public class AccountAdapter extends BaseAdapter {

    private static final int COLOR_NORMAL = 0xFF111111;
    private static final int COLOR_EXPIRING = 0xFFE65100;

    private final LayoutInflater inflater;
    private final List<Account> items = new ArrayList<Account>();

    public AccountAdapter(Context ctx) {
        inflater = LayoutInflater.from(ctx);
    }

    public void setItems(List<Account> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Account getItem(int position) {
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
            row = inflater.inflate(R.layout.item_account, parent, false);
        }
        Account a = items.get(position);
        TextView tvIssuer = (TextView) row.findViewById(R.id.tv_issuer);
        TextView tvAccount = (TextView) row.findViewById(R.id.tv_account);
        tvIssuer.setText(a.displayTitle());
        tvAccount.setText(a.account);
        bindCode(row, a, Prefs.get(row.getContext()), System.currentTimeMillis());
        row.setTag(Integer.valueOf(position));
        return row;
    }

    /** 供列表的每秒 ticker 复用的轻量刷新逻辑。 */
    public static void bindCode(View row, Account a, Prefs prefs, long now) {
        TextView tvCode = (TextView) row.findViewById(R.id.tv_code);
        ProgressBar pb = (ProgressBar) row.findViewById(R.id.pb);
        if (a == null) {
            tvCode.setText("");
            return;
        }
        byte[] key = Base32.decode(a.secret);
        if (a.isHotp()) {
            tvCode.setText(Otp.format(Otp.generate(key, a.counter, a.digits, a.algorithm)));
            tvCode.setTextColor(COLOR_NORMAL);
            pb.setVisibility(View.GONE);
            return;
        }
        int offset = prefs == null ? 0 : prefs.timeOffset();
        long counter = Otp.totpCounter(now, a.period, offset);
        int remain = Otp.remainingSeconds(now, a.period, offset);
        tvCode.setText(Otp.format(Otp.generate(key, counter, a.digits, a.algorithm)));
        tvCode.setTextColor(remain <= 5 ? COLOR_EXPIRING : COLOR_NORMAL);
        pb.setVisibility(View.VISIBLE);
        pb.setMax(a.period > 0 ? a.period : 30);
        pb.setProgress(remain);
    }

}
