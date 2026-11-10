package com.mcxgjkh.lockscreen;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import com.mcxgjkh.lockscreen.core.Clips;

/**
 * 给同签名应用（authenticator）用的剪贴板入口。
 *
 * 为什么需要它：这台设备的 ROM 没有 ClipboardService，任何应用都写不进系统剪贴板。
 * 于是把内容送进本应用自己的剪贴板存储，用户再用悬浮球的「粘贴」注入到
 * 当前输入框（ASCII 走 `input text`，不依赖剪贴板服务）。
 *
 * 安全：provider 声明了 signature 级权限，只有同签名应用能调用。
 */
public class ClipProvider extends ContentProvider {

    public static final String AUTHORITY = "com.mcxgjkh.lockscreen.clip";
    public static final String PERMISSION = "com.mcxgjkh.lockscreen.permission.CLIP";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/current");

    private static final int CURRENT = 1;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTHORITY, "current", CURRENT);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{"text", "captures"});
        String text = Clips.current(getContext());
        c.addRow(new Object[]{text, Clips.captureCount(getContext())});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        String text = values == null ? null : values.getAsString("text");
        if (text == null) {
            return null;
        }
        // 只存本地：本入口就是为了绕开系统没有剪贴板的情况
        Clips.setLocal(getContext(), text);
        return URI;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return insert(uri, values) == null ? 0 : 1;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        Clips.clearHistory(getContext());
        return 1;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.com.mcxgjkh.lockscreen.clip";
    }
}
