#!/usr/bin/env python3
"""Generate com.mndiad.android.sdk.R from the app's aapt2-generated R.

The Mondiad AAR's compiled classes reference their own library R
(R$drawable, R$styleable). In a manual (no-Gradle) build there is no
Gradle library-R generation, so we copy the resource IDs that aapt2
assigned to the merged resource table (which includes the SDK's res)
into the SDK's package. IDs match because both Rs describe the same
final resources.arsc.
"""
import re
import sys

src_path, out_path = sys.argv[1], sys.argv[2]
src = open(src_path).read()
src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)

classes = {}
for m in re.finditer(r"public static final class (\w+) \{(.*?)\n  \}", src, re.S):
    classes[m.group(1)] = m.group(2).strip()

wanted = {
    # SDK references R$drawable and R$styleable; attr/style copied too
    # so any lazily-resolved reference also resolves.
    "attr": classes.get("attr", ""),
    "drawable": classes.get("drawable", ""),
    "style": classes.get("style", ""),
    "styleable": classes.get("styleable", ""),
}

# Keep only SDK-owned entries where a class mixes app + library resources.
def keep(block: str, prefixes: tuple[str, ...]) -> str:
    lines = []
    # styleable arrays span multiple lines; handle statements, not lines.
    for stmt in block.split(";"):
        s = stmt.strip()
        if not s:
            continue
        if any(p in s for p in prefixes):
            lines.append("    " + s.replace("\n", " ").strip() + ";")
    return "\n".join(lines)

out = {
    "attr": keep(wanted["attr"], ("zone_id", "position", "size", "offset", "apply_safe_offset")),
    "drawable": keep(wanted["drawable"], ("mnd_",)),
    "style": keep(wanted["style"], ("Theme_Transparent",)),
    "styleable": keep(wanted["styleable"], ("BannerView",)),
}

missing = [k for k, v in out.items() if k in ("drawable", "styleable") and not v]
if missing:
    sys.exit(f"ERROR: SDK R fields missing from app R: {missing}")

body = "\n".join(
    f"  public static final class {k} {{\n{v}\n  }}" for k, v in out.items() if v
)
with open(out_path, "w") as f:
    f.write(
        "package com.mndiad.android.sdk;\n\n"
        "public final class R {\n" + body + "\n}\n"
    )
print("SDK R generated:", {k: v.count(";") for k, v in out.items() if v})
