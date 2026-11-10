#!/usr/bin/env python3
"""从 res/ 目录生成编译校验用的 R.java（仅供 javac 检查，不参与正式构建）。"""
import glob
import os
import re
import sys

SKIP_TYPES = {"values", "values-v21", "values-v23", "raw"}


def collect(res_dir):
    out = {}
    for f in glob.glob(os.path.join(res_dir, "values", "*.xml")):
        s = open(f, encoding="utf-8").read()
        for m in re.finditer(r'<(string|color|dimen|style|string-array|integer|bool|attr)\s+name="([^"]+)"', s):
            typ, name = m.group(1), m.group(2)
            if typ == "string-array":
                typ = "array"
            out.setdefault(typ, set()).add(name.replace(".", "_"))
    for d in sorted(os.listdir(res_dir)):
        p = os.path.join(res_dir, d)
        if not os.path.isdir(p) or d in SKIP_TYPES:
            continue
        typ = d.split("-")[0]
        for f in os.listdir(p):
            out.setdefault(typ, set()).add(f.split(".")[0])
    for f in glob.glob(os.path.join(res_dir, "layout", "*.xml")):
        s = open(f, encoding="utf-8").read()
        for m in re.finditer(r"@\+id/([A-Za-z0-9_]+)", s):
            out.setdefault("id", set()).add(m.group(1))
    return out


def emit(package, res, target):
    lines = ["package %s;" % package, "", "public final class R {"]
    value = 0x7F010000
    for typ in sorted(res):
        lines.append("    public static final class %s {" % typ)
        for name in sorted(res[typ]):
            lines.append("        public static final int %s = 0x%X;" % (name, value))
            value += 1
        lines.append("    }")
    lines.append("}")
    with open(target, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    total = sum(len(v) for v in res.values())
    print("R.java for %s: %d symbols, %d types" % (package, total, len(res)))


if __name__ == "__main__":
    project, package, target = sys.argv[1], sys.argv[2], sys.argv[3]
    emit(package, collect(os.path.join(project, "app", "src", "main", "res")), target)
