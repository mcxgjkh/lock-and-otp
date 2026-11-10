package com.mcxgjkh.clip;

import android.content.ClipData;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ServiceManager;

/** 端到端自测：只走 binder 线格式，不依赖任何应用。 */
public class ClipClientTest {

    private static final String D = "android.content.IClipboard";

    public static void main(String[] args) {
        try {
            IBinder b = ServiceManager.getService("clipboard");
            if (b == null) {
                System.out.println("FAIL: service not found");
                return;
            }
            System.out.println("service=ok");

            // 注册监听，验证回调
            final boolean[] fired = new boolean[]{false};
            IBinder listener = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    if (code == 1) {
                        fired[0] = true;
                        System.out.println("listener: dispatchPrimaryClipChanged");
                    }
                    return true;
                }
            };
            Parcel d = Parcel.obtain();
            Parcel r = Parcel.obtain();
            d.writeInterfaceToken(D);
            d.writeStrongBinder(listener);
            d.writeString("com.test");
            b.transact(5, d, r, 0);
            r.readException();
            d.recycle();
            r.recycle();

            // set
            String text = "wb-clip-中文-123";
            d = Parcel.obtain();
            r = Parcel.obtain();
            d.writeInterfaceToken(D);
            d.writeInt(1);                                  // presence int（AIDL 可空对象）
            ClipData.newPlainText("t", text).writeToParcel(d, 0);
            d.writeString("com.test");
            b.transact(1, d, r, 0);
            r.readException();
            d.recycle();
            r.recycle();

            // has
            d = Parcel.obtain();
            r = Parcel.obtain();
            d.writeInterfaceToken(D);
            d.writeString("com.test");
            b.transact(4, d, r, 0);
            r.readException();
            System.out.println("hasPrimaryClip=" + (r.readInt() != 0));
            d.recycle();
            r.recycle();

            // get
            d = Parcel.obtain();
            r = Parcel.obtain();
            d.writeInterfaceToken(D);
            d.writeString("com.test");
            b.transact(2, d, r, 0);
            r.readException();
            int present = r.readInt();
            ClipData back = present != 0 ? ClipData.CREATOR.createFromParcel(r) : null;
            String got = back == null || back.getItemCount() == 0
                    ? null : String.valueOf(back.getItemAt(0).getText());
            System.out.println("getPrimaryClip=" + got);
            System.out.println("roundtrip=" + text.equals(got));
            System.out.println("listenerFired=" + fired[0]);
            d.recycle();
            r.recycle();
        } catch (Throwable t) {
            System.out.println("FAIL " + t);
        }
    }
}
