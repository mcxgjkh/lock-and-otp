package com.mcxgjkh.clip;

import android.content.ClipData;
import android.content.ClipDescription;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ServiceManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 给缺少 ClipboardService 的 ROM 补一个剪贴板服务。
 *
 * 原理：以 root 启动 app_process，向 servicemanager 注册名为 "clipboard" 的 binder，
 * 按 API 25 的 IClipboard 事务号实现 7 个方法。应用侧 ClipboardManager 走的是同一套
 * binder 调用，因此不需要改任何应用。
 *
 * 事务号来自设备实测（android.content.IClipboard$Stub）：
 *   1 setPrimaryClip(ClipData, String)
 *   2 getPrimaryClip(String) -> ClipData
 *   3 getPrimaryClipDescription(String) -> ClipDescription
 *   4 hasPrimaryClip(String) -> boolean
 *   5 addPrimaryClipChangedListener(IOnPrimaryClipChangedListener, String)
 *   6 removePrimaryClipChangedListener(IOnPrimaryClipChangedListener)
 *   7 hasClipboardText(String) -> boolean
 */
public final class ClipDaemon {

    private static final String DESCRIPTOR = "android.content.IClipboard";
    private static final String LISTENER_DESCRIPTOR =
            "android.content.IOnPrimaryClipChangedListener";
    private static final String SERVICE_NAME = "clipboard";

    private static final Object LOCK = new Object();
    private static final List<IBinder> LISTENERS = new ArrayList<IBinder>();

    private static ClipData sClip;
    private static File sStore;

    private ClipDaemon() {
    }

    public static void main(String[] args) {
        sStore = new File(args != null && args.length > 0 ? args[0] : "/data/adb/wbclip/clip.txt");
        load();

        // 系统里已经有这个服务就别抢
        try {
            if (ServiceManager.getService(SERVICE_NAME) != null) {
                System.out.println("clipboard already present, exit");
                return;
            }
        } catch (Throwable ignored) {
            // 读不到就继续尝试注册
        }

        Binder service = new Service();
        boolean ok = false;
        for (int i = 0; i < 120 && !ok; i++) {
            try {
                ServiceManager.addService(SERVICE_NAME, service);
                ok = ServiceManager.getService(SERVICE_NAME) != null;
                if (ok) {
                    System.out.println("clipboard registered");
                }
            } catch (Throwable t) {
                if (i % 10 == 0) {
                    System.out.println("add failed: " + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }
            }
            if (!ok) {
                sleep(500L);
            }
        }
        if (!ok) {
            System.out.println("give up: cannot register");
            return;
        }
        // 常驻：binder 线程由 Binder 自己维护，这里只需要不退出
        while (true) {
            sleep(600000L);
        }
    }

    // ---------------------------------------------------------------- binder

    private static final class Service extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                switch (code) {
                    case 1: {                       // setPrimaryClip
                        data.enforceInterface(DESCRIPTOR);
                        int callerUid = Binder.getCallingUid();
                        int callerPid = Binder.getCallingPid();
                        System.out.println("set called by uid=" + callerUid
                                + " pid=" + callerPid + " size=" + data.dataSize());
                        int p0 = data.dataPosition();
                        // AIDL 可空 Parcelable：先读 presence int，再读对象
                        int present = data.readInt();
                        ClipData clip = present != 0
                                ? ClipData.CREATOR.createFromParcel(data) : null;
                        int p1 = data.dataPosition();
                        String pkg = data.readString();      // callingPackage
                        int p2 = data.dataPosition();
                        System.out.println("pos " + p0 + "->" + p1 + "->" + p2
                                + " size=" + data.dataSize() + " present=" + present
                                + " items=" + (clip == null ? -1 : clip.getItemCount())
                                + " label=" + (clip == null || clip.getDescription() == null
                                        ? "?" : String.valueOf(clip.getDescription().getLabel())));
                        int len = (clip != null && clip.getItemCount() > 0
                                && clip.getItemAt(0).getText() != null)
                                ? clip.getItemAt(0).getText().length() : -1;
                        System.out.println("set from=" + pkg + " items="
                                + (clip == null ? -1 : clip.getItemCount()) + " textLen=" + len);
                        setClip(clip);
                        if (reply != null) {
                            reply.writeNoException();
                        }
                        return true;
                    }
                    case 2: {                       // getPrimaryClip
                        data.enforceInterface(DESCRIPTOR);
                        data.readString();
                        if (reply != null) {
                            reply.writeNoException();
                            writeClip(reply);
                        }
                        return true;
                    }
                    case 3: {                       // getPrimaryClipDescription
                        data.enforceInterface(DESCRIPTOR);
                        data.readString();
                        if (reply != null) {
                            reply.writeNoException();
                            ClipDescription desc = null;
                            synchronized (LOCK) {
                                if (sClip != null) {
                                    desc = sClip.getDescription();
                                }
                            }
                            if (desc == null) {
                                reply.writeInt(0);
                            } else {
                                reply.writeInt(1);
                                desc.writeToParcel(reply, 0);
                            }
                        }
                        return true;
                    }
                    case 4: {                       // hasPrimaryClip
                        data.enforceInterface(DESCRIPTOR);
                        data.readString();
                        boolean has;
                        synchronized (LOCK) {
                            has = sClip != null;
                        }
                        if (reply != null) {
                            reply.writeNoException();
                            reply.writeInt(has ? 1 : 0);
                        }
                        return true;
                    }
                    case 5: {                       // addPrimaryClipChangedListener
                        data.enforceInterface(DESCRIPTOR);
                        IBinder listener = data.readStrongBinder();
                        data.readString();
                        if (listener != null) {
                            synchronized (LOCK) {
                                if (!LISTENERS.contains(listener)) {
                                    LISTENERS.add(listener);
                                }
                            }
                        }
                        if (reply != null) {
                            reply.writeNoException();
                        }
                        return true;
                    }
                    case 6: {                       // removePrimaryClipChangedListener
                        data.enforceInterface(DESCRIPTOR);
                        IBinder listener = data.readStrongBinder();
                        if (listener != null) {
                            synchronized (LOCK) {
                                LISTENERS.remove(listener);
                            }
                        }
                        if (reply != null) {
                            reply.writeNoException();
                        }
                        return true;
                    }
                    case 7: {                       // hasClipboardText
                        data.enforceInterface(DESCRIPTOR);
                        data.readString();
                        boolean has;
                        synchronized (LOCK) {
                            has = sClip != null && sClip.getItemCount() > 0
                                    && sClip.getItemAt(0).getText() != null;
                        }
                        if (reply != null) {
                            reply.writeNoException();
                            reply.writeInt(has ? 1 : 0);
                        }
                        return true;
                    }
                    default:
                        return super.onTransact(code, data, reply, flags);
                }
            } catch (Throwable t) {
                // 任何解析异常都不能让调用方卡死：回一个空结果
                if (reply != null) {
                    try {
                        reply.writeNoException();
                        reply.writeInt(0);
                    } catch (Throwable ignored) {
                        // reply 已失效
                    }
                }
                System.out.println("transact " + code + " error: " + t);
                t.printStackTrace(System.out);
                return true;
            }
        }
    }

    private static void writeClip(Parcel reply) {
        ClipData clip;
        synchronized (LOCK) {
            clip = sClip;
        }
        if (clip == null) {
            reply.writeInt(0);
        } else {
            reply.writeInt(1);
            clip.writeToParcel(reply, 0);
        }
    }

    private static void setClip(ClipData clip) {
        synchronized (LOCK) {
            sClip = clip;
        }
        save(clip);
        notifyListeners();
    }

    private static void notifyListeners() {
        List<IBinder> copy;
        synchronized (LOCK) {
            copy = new ArrayList<IBinder>(LISTENERS);
        }
        for (IBinder l : copy) {
            if (l == null || !l.isBinderAlive()) {
                synchronized (LOCK) {
                    LISTENERS.remove(l);
                }
                continue;
            }
            Parcel d = Parcel.obtain();
            try {
                d.writeInterfaceToken(LISTENER_DESCRIPTOR);
                l.transact(1, d, null, IBinder.FLAG_ONEWAY);
            } catch (Throwable ignored) {
                // 监听方已死，下一次清理
            } finally {
                d.recycle();
            }
        }
    }

    // ---------------------------------------------------------------- 持久化

    private static void load() {
        FileInputStream in = null;
        try {
            if (!sStore.exists()) {
                return;
            }
            in = new FileInputStream(sStore);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 1 << 20) {
                    break;
                }
            }
            String text = new String(bos.toByteArray(), "UTF-8");
            if (text.length() > 0) {
                sClip = ClipData.newPlainText("clipboard", text);
            }
        } catch (Throwable t) {
            System.out.println("load failed: " + t);
        } finally {
            close(in);
        }
    }

    private static void save(ClipData clip) {
        if (sStore == null) {
            return;
        }
        String text = "";
        try {
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                text = clip.getItemAt(0).getText().toString();
            }
        } catch (Throwable ignored) {
            // 非文本内容不落盘
        }
        FileOutputStream out = null;
        try {
            File dir = sStore.getParentFile();
            if (dir != null && !dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            out = new FileOutputStream(sStore);
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable t) {
            System.out.println("save failed: " + t);
        } finally {
            close(out);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
