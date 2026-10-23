#!/usr/bin/env python3
"""Download the Kotlin 2.4.20 compiler and copy the host jar for tools/build.py.

Everything lands in build/tools and stays out of Git.
"""

from __future__ import annotations

import argparse
import os
import shutil
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / "build" / "tools"
KOTLIN_VERSION = "2.4.20"


def download(url: str, target: Path) -> None:
    if target.exists() and target.stat().st_size > 0:
        print("已缓存", target)
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    print("下载", url)
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    part = target.with_suffix(target.suffix + ".part")
    with urllib.request.urlopen(request, timeout=600) as response, open(part, "wb") as handle:
        shutil.copyfileobj(response, handle, 1024 * 1024)
    part.replace(target)


def kotlin() -> None:
    home = TOOLS / "kotlin" / "kotlinc"
    if (home / "lib" / "compose-compiler-plugin.jar").exists():
        print("已缓存", home)
        return
    archive = TOOLS / "kotlin-compiler.zip"
    download(
        "https://github.com/JetBrains/kotlin/releases/download/"
        f"v{KOTLIN_VERSION}/kotlin-compiler-{KOTLIN_VERSION}.zip",
        archive,
    )
    target = TOOLS / "kotlin"
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as bundle:
        bundle.extractall(target)
    if not (home / "lib" / "compose-compiler-plugin.jar").exists():
        raise SystemExit(f"解压后未找到 kotlinc: {home}")
    print("解压完成", home)


def host_jar(spw_dir: Path) -> None:
    source = spw_dir / "app" / "ffmpeg-x64.dll"
    target = TOOLS / "spw-host.jar"
    if not source.is_file():
        raise SystemExit(f"未找到宿主文件: {source}")
    if target.exists() and target.stat().st_size == source.stat().st_size:
        print("已缓存", target)
        return
    shutil.copyfile(source, target)
    print("复制", source, "->", target)


def main() -> None:
    default_spw = os.environ.get("SPW_DIR", r"D:\SteamLibrary\steamapps\common\Salt Player for Windows")
    parser = argparse.ArgumentParser()
    parser.add_argument("--spw-dir", type=Path, default=Path(default_spw),
                        help="Salt Player for Windows 安装目录")
    args = parser.parse_args()
    TOOLS.mkdir(parents=True, exist_ok=True)
    kotlin()
    host_jar(args.spw_dir)
    print("工具链就绪:", TOOLS)


if __name__ == "__main__":
    main()
