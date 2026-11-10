package com.mcxgjkh.lockscreen.core;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 无障碍服务管理：解决"部分手机/手表没有无障碍设置界面，导致依赖无障碍的软件无法启用"。
 *
 * 列表来源有三个，按可信度从高到低合并：
 *   1. PackageManager 查询声明了 accessibilityservice 的 intent-filter 的服务
 *      —— 不受"系统无障碍列表"过滤，能列出那些被 ROM 漏掉的第三方服务；
 *   2. AccessibilityManager.getInstalledAccessibilityServiceList()
 *      —— 系统自己认的列表，标记为 recognized；
 *   3. includeAll 时枚举**所有已安装应用的所有服务**，供极端情况下手工启用。
 *
 * 开关写回 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES / ACCESSIBILITY_ENABLED，
 * 与系统设置页操作完全等价。组件名会统一成 "pkg/full.Cls" 并做字符白名单校验。
 */
public final class AccessibilityTool {

    public static final String KEY_ENABLED_SERVICES = "enabled_accessibility_services";
    public static final String KEY_ACCESSIBILITY_ENABLED = "accessibility_enabled";

    /** 无障碍服务必须声明的权限；未声明的服务系统会拒绝绑定。 */
    public static final String PERM_BIND = "android.permission.BIND_ACCESSIBILITY_SERVICE";

    public static final class Item {
        public final String pkg;
        public final String cls;
        public final String flatten;
        public final String label;
        /** 是否声明了 BIND_ACCESSIBILITY_SERVICE 权限（系统只会绑定这类服务）。 */
        public final boolean declared;
        /** 是否出现在系统自己的无障碍服务列表里。 */
        public final boolean recognized;

        Item(String pkg, String cls, String label, boolean declared, boolean recognized) {
            this.pkg = pkg;
            this.cls = cls;
            this.flatten = pkg + "/" + cls;
            this.label = label;
            this.declared = declared;
            this.recognized = recognized;
        }
    }

    private AccessibilityTool() {
    }

    public static List<Item> list(Context ctx) {
        return list(ctx, false);
    }

    public static List<Item> list(Context ctx, boolean includeAll) {
        Map<String, Item> map = new LinkedHashMap<String, Item>();
        PackageManager pm = ctx.getPackageManager();

        // 1) 声明了无障碍 intent-filter 的服务（含未声明权限的，能暴露"看不见"的应用）
        try {
            Intent probe = new Intent("android.accessibilityservice.AccessibilityService");
            List<ResolveInfo> ris = pm.queryIntentServices(probe, 0);
            if (ris != null) {
                for (ResolveInfo ri : ris) {
                    if (ri != null && ri.serviceInfo != null) {
                        put(map, pm, ri.serviceInfo, false);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 个别 ROM 限制查询
        }

        // 2) 系统已识别列表：补上 recognized 标记
        try {
            AccessibilityManager am =
                    (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            List<AccessibilityServiceInfo> infos =
                    am == null ? null : am.getInstalledAccessibilityServiceList();
            if (infos != null) {
                for (AccessibilityServiceInfo info : infos) {
                    if (info == null || info.getResolveInfo() == null
                            || info.getResolveInfo().serviceInfo == null) {
                        continue;
                    }
                    put(map, pm, info.getResolveInfo().serviceInfo, true);
                }
            }
        } catch (Throwable ignored) {
            // 无障碍服务本身不可用时忽略
        }

        // 3) 全部应用的所有服务（默认不启用，列表会很长）
        if (includeAll) {
            try {
                List<PackageInfo> pkgs = pm.getInstalledPackages(PackageManager.GET_SERVICES);
                if (pkgs != null) {
                    for (PackageInfo pi : pkgs) {
                        if (pi == null || pi.services == null) {
                            continue;
                        }
                        for (ServiceInfo si : pi.services) {
                            if (si != null && si.packageName != null && si.name != null) {
                                put(map, pm, si, false);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 查询失败就只显示前两个来源
            }
        }

        List<Item> out = new ArrayList<Item>(map.values());
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (a.declared != b.declared) {
                    return a.declared ? -1 : 1;
                }
                if (a.recognized != b.recognized) {
                    return a.recognized ? -1 : 1;
                }
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    public static Set<String> enabledSet(Context ctx) {
        Set<String> set = new LinkedHashSet<String>();
        String raw = null;
        try {
            raw = Settings.Secure.getString(ctx.getContentResolver(), KEY_ENABLED_SERVICES);
        } catch (Throwable ignored) {
            // 读取失败按空处理
        }
        if (raw == null || raw.length() == 0) {
            return set;
        }
        for (String part : raw.split(":")) {
            String n = normalize(part);
            if (n.length() > 0) {
                set.add(n);
            }
        }
        return set;
    }

    public static boolean isEnabled(Context ctx, Item item) {
        return enabledSet(ctx).contains(item.flatten);
    }

    public static boolean setEnabled(Context ctx, Item item, boolean enabled) {
        if (item == null) {
            return false;
        }
        Set<String> set = enabledSet(ctx);
        if (enabled) {
            set.add(item.flatten);
        } else {
            set.remove(item.flatten);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(s);
        }
        boolean ok = SecureSettings.put(ctx, KEY_ENABLED_SERVICES, sb.toString());
        ok &= SecureSettings.put(ctx, KEY_ACCESSIBILITY_ENABLED, set.isEmpty() ? "0" : "1");
        return ok;
    }

    /** 手工输入的组件名 → Item；格式非法时返回 null。 */
    public static Item fromComponent(Context ctx, String input) {
        String n = normalize(input);
        int slash = n.indexOf('/');
        if (slash <= 0 || slash >= n.length() - 1) {
            return null;
        }
        String pkg = n.substring(0, slash);
        String cls = n.substring(slash + 1);
        if (!isSafeName(pkg) || !isSafeName(cls)) {
            return null;
        }
        boolean declared = false;
        try {
            declared = PERM_BIND.equals(
                    ctx.getPackageManager().getServiceInfo(
                            new android.content.ComponentName(pkg, cls), 0).permission);
        } catch (Throwable ignored) {
            // 组件不存在或无权限查看
        }
        return new Item(pkg, cls, pkg, declared, false);
    }

    /** 统一成 "pkg/full.Cls" 形式，避免 ".Service" 简写与全名被当成两个条目。 */
    static String normalize(String flatten) {
        if (flatten == null) {
            return "";
        }
        String s = flatten.trim();
        int slash = s.indexOf('/');
        if (slash <= 0 || slash == s.length() - 1) {
            return s;
        }
        String pkg = s.substring(0, slash);
        String cls = s.substring(slash + 1);
        if (cls.startsWith(".")) {
            cls = pkg + cls;
        }
        return pkg + "/" + cls;
    }

    // ---------------------------------------------------------------- internal

    private static void put(Map<String, Item> map, PackageManager pm,
                            ServiceInfo si, boolean recognized) {
        if (si == null || si.packageName == null || si.name == null) {
            return;
        }
        String flatten = si.packageName + "/" + si.name;
        Item exist = map.get(flatten);
        boolean declared = PERM_BIND.equals(si.permission);
        if (exist != null) {
            if (recognized && !exist.recognized) {
                map.put(flatten, new Item(exist.pkg, exist.cls, exist.label,
                        exist.declared, true));
            }
            return;
        }
        String label = null;
        try {
            CharSequence cs = si.loadLabel(pm);
            label = cs == null ? null : cs.toString().trim();
        } catch (Throwable ignored) {
            // 少数 ROM 会抛
        }
        if (label == null || label.length() == 0) {
            try {
                CharSequence cs = pm.getApplicationLabel(
                        pm.getApplicationInfo(si.packageName, 0));
                label = cs == null ? null : cs.toString().trim();
            } catch (Throwable ignored) {
                // 应用被卸载
            }
        }
        if (label == null || label.length() == 0) {
            label = si.name;
        }
        map.put(flatten, new Item(si.packageName, si.name, label, declared, recognized));
    }

    private static boolean isSafeName(String s) {
        if (s.length() == 0 || s.length() > 200) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
