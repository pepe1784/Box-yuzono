#!/usr/bin/env python3
import json
import os
import re

BUILD_FILE = "src/all/box/build.gradle"

with open(BUILD_FILE, "r", encoding="utf-8") as f:
    build_text = f.read()


def get_ext_int(name: str) -> int:
    match = re.search(rf"{name}\s*=\s*(\d+)", build_text)
    if not match:
        raise ValueError(f"Could not find {name} in {BUILD_FILE}")
    return int(match.group(1))


EXT_NAME = "box"
LANG = "all"
PKG = f"eu.kanade.tachiyomi.animeextension.{LANG}.{EXT_NAME}"
VERSION_CODE = get_ext_int("extVersionCode")
VERSION_NAME = f"14.{VERSION_CODE}"
APK_NAME = f"aniyomi-{LANG}.{EXT_NAME}-v{VERSION_NAME}.apk"

index = [
    {
        "name": f"Aniyomi: {EXT_NAME}",
        "pkg": PKG,
        "apk": APK_NAME,
        "lang": LANG,
        "code": VERSION_CODE,
        "version": VERSION_NAME,
        "nsfw": 0,
        "sources": [
            {
                "name": EXT_NAME,
                "lang": LANG,
                "id": "9134775860771942682",
                "baseUrl": "",
                "versionId": 1,
            }
        ],
    }
]

os.makedirs("repo/apk", exist_ok=True)
with open("repo/index.min.json", "w", encoding="utf-8") as f:
    json.dump(index, f, separators=(",", ":"))

src_apk = f"src/all/box/build/outputs/apk/release/{APK_NAME}"
dst_apk = f"repo/apk/{APK_NAME}"
if os.path.exists(src_apk):
    with open(src_apk, "rb") as src, open(dst_apk, "wb") as dst:
        dst.write(src.read())
    print(f"Copied {src_apk} -> {dst_apk}")
else:
    print(f"WARNING: {src_apk} not found")

print(f"Generated repo/index.min.json for {APK_NAME}")
