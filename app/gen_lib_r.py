#!/usr/bin/env python3
"""Generate a library R class (in the library's own package) from the
app's aapt2-generated R, copying the resource IDs aapt2 assigned in the
merged resource table. Used for AARs consumed by the manual build
(Mondiad SDK, aniyomi-mpv-lib), whose compiled classes reference their
own R.

Usage: gen_lib_r.py <app-R.java> <out.java> <package> <spec>
  spec: semicolon-separated "class:prefix1,prefix2" entries, e.g.
        "drawable:mnd_;style:Theme_Transparent;styleable:BannerView"
        A prefix of "*" copies the whole class.
"""
import re
import sys

src_path, out_path, package, spec = sys.argv[1:5]
src = open(src_path).read()
src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)

classes = {}
for m in re.finditer(r"public static final class (\w+) \{(.*?)\n  \}", src, re.S):
    classes[m.group(1)] = m.group(2).strip()


def keep(block: str, prefixes: list[str]) -> str:
    if prefixes == ["*"]:
        return "\n".join(
            "    " + s.strip().replace("\n", " ") + ";"
            for s in block.split(";") if s.strip()
        )
    lines = []
    for stmt in block.split(";"):
        s = stmt.strip()
        if s and any(p in s for p in prefixes):
            lines.append("    " + s.replace("\n", " ").strip() + ";")
    return "\n".join(lines)


parts = []
counts = {}
for entry in spec.split(";"):
    cls, _, prefixes = entry.partition(":")
    block = classes.get(cls, "")
    kept = keep(block, prefixes.split(",")) if block else ""
    if kept:
        parts.append(f"  public static final class {cls} {{\n{kept}\n  }}")
        counts[cls] = kept.count(";")

if not parts:
    sys.exit("ERROR: no R fields matched spec")

with open(out_path, "w") as f:
    f.write(f"package {package};\n\npublic final class R {{\n" + "\n".join(parts) + "\n}\n")
print(f"{package}.R generated:", counts)
