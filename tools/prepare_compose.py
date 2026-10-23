#!/usr/bin/env python3
"""Download the Compose Multiplatform 1.12.1 jars used only for compilation.

The player's own jar is R8 processed; Compose declarations needed by the Kotlin
compiler are not all resolvable from it. These artifacts sit in front of the host
jar on the compile classpath and are never packaged into the .spmod.

Note: ``org.jetbrains.compose.runtime:runtime-desktop`` is an empty forwarding
artifact; the real runtime comes from Google Maven as
``androidx.compose.runtime:runtime-desktop``.
"""

from __future__ import annotations

import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "build" / "tools" / "compose"
VERSION = "1.12.1"

CMP_ARTIFACTS = [
    ("org/jetbrains/compose/ui", "ui-desktop"),
    ("org/jetbrains/compose/ui", "ui-graphics-desktop"),
    ("org/jetbrains/compose/ui", "ui-text-desktop"),
    ("org/jetbrains/compose/ui", "ui-unit-desktop"),
    ("org/jetbrains/compose/ui", "ui-geometry-desktop"),
    ("org/jetbrains/compose/ui", "ui-util-desktop"),
    ("org/jetbrains/compose/animation", "animation-desktop"),
    ("org/jetbrains/compose/animation", "animation-core-desktop"),
    ("org/jetbrains/compose/foundation", "foundation-desktop"),
    ("org/jetbrains/compose/foundation", "foundation-layout-desktop"),
    ("org/jetbrains/compose/material", "material-ripple-desktop"),
]

ANDROIDX_ARTIFACTS = [
    ("androidx/compose/runtime", "runtime-desktop"),
    ("androidx/compose/runtime", "runtime-saveable-desktop"),
]


def download(url: str, target: Path, minimum_size: int = 1024) -> None:
    if target.exists() and target.stat().st_size >= minimum_size:
        print("已缓存", target.name)
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    print("下载", url)
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    part = target.with_suffix(target.suffix + ".part")
    with urllib.request.urlopen(request, timeout=300) as response, open(part, "wb") as handle:
        handle.write(response.read())
    part.replace(target)
    print("完成", target.name, target.stat().st_size, "字节")


def main() -> None:
    TARGET.mkdir(parents=True, exist_ok=True)
    failed: list[str] = []
    for group, artifact in CMP_ARTIFACTS:
        name = f"{artifact}-{VERSION}.jar"
        try:
            download(f"https://repo1.maven.org/maven2/{group}/{artifact}/{VERSION}/{name}", TARGET / name)
        except Exception as error:  # noqa: BLE001 - report and continue
            failed.append(f"{artifact}: {error}")
            print("失败", artifact, error)
    for group, artifact in ANDROIDX_ARTIFACTS:
        name = f"androidx-{artifact}-{VERSION}.jar"
        try:
            download(
                f"https://dl.google.com/android/maven2/{group}/{artifact}/{VERSION}/{artifact}-{VERSION}.jar",
                TARGET / name,
                minimum_size=200_000,
            )
        except Exception as error:  # noqa: BLE001 - report and continue
            failed.append(f"{artifact}: {error}")
            print("失败", artifact, error)
    jars = sorted(TARGET.glob("*.jar"))
    print(f"{len(jars)} 个 Compose 编译 jar 位于 {TARGET}")
    if failed:
        print("以下依赖下载失败，请检查网络后重试：")
        for item in failed:
            print("  -", item)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
