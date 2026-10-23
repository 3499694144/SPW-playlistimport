#!/usr/bin/env python3
"""Build the 歌单导入 (playlist import) .spmod without Gradle.

The player ships its own fat jar (``app/ffmpeg-x64.dll``) containing Kotlin,
Compose, Room, PF4J and the Workshop API. Compiling against exactly that jar
keeps every host reference in lockstep with the running player. Compose
Multiplatform jars are used for compilation only and never packaged.

Usage:
    python tools/build.py --spw-dir "D:\\SteamLibrary\\steamapps\\common\\Salt Player for Windows"

Optional:
    --host-jar PATH      use an extracted host jar instead of the installation
    --kotlin-home PATH   kotlinc directory (default: build/tools/kotlin/kotlinc)
    --compose-dir PATH   Compose compilation jars (default: build/tools/compose)
    --jdk PATH           JDK 25 home (default: JAVA_HOME or the usual install dirs)
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import zipfile
from datetime import datetime
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / "build"
CLASSES = BUILD / "plugin-classes"
TOOLS = BUILD / "tools"
JAVA_SOURCES = ROOT / "src" / "main" / "java"
KOTLIN_SOURCES = ROOT / "src" / "main" / "kotlin"
RESOURCES = ROOT / "src" / "main" / "resources"

PLUGIN_ID = "spw.playlistimport"
PLUGIN_VERSION = "2.0.0"
PLUGIN_NAME = "歌单导入"
PLUGIN_CLASS = "local.saltplaylist.MainPlugin"
PLUGIN_DESCRIPTION = "导入“歌名 - 歌手”文本歌单到 SPW，歌单名取自文件名"
PLUGIN_URL = "https://github.com/aikavvak12una/SPW-playlistimport"
PACKAGE_FILENAME = "spw.playlistimport.spmod"

JDK_HINTS = [
    Path(r"C:\Program Files\BellSoft\LibericaJDK-25"),
    Path(r"C:\Program Files\Eclipse Adoptium\jdk-25"),
    Path(r"C:\Program Files\Java\jdk-25"),
]


def fail(message: str) -> NoReturn:
    raise SystemExit(message)


def find_jdk(explicit: Path | None) -> Path:
    candidates = [explicit] if explicit else []
    java_home = Path(os.environ.get("JAVA_HOME", "")) if os.environ.get("JAVA_HOME") else None
    candidates += [java_home, *JDK_HINTS]
    for candidate in candidates:
        if candidate and (candidate / "bin" / "javac.exe").exists():
            return candidate
    fail("未找到 JDK 25，请用 --jdk 指定，或设置 JAVA_HOME")


def find_kotlin(explicit: Path | None) -> Path:
    candidates = [explicit] if explicit else []
    env_home = Path(os.environ["KOTLIN_HOME"]) if os.environ.get("KOTLIN_HOME") else None
    candidates += [env_home, TOOLS / "kotlin" / "kotlinc"]
    for candidate in candidates:
        if candidate and (candidate / "lib" / "compose-compiler-plugin.jar").exists():
            return candidate
    fail("未找到 kotlinc 2.4.20，请运行 tools/prepare_toolchain.py 或用 --kotlin-home 指定")


def compose_jars(explicit: Path | None) -> list[Path]:
    directory = explicit or (TOOLS / "compose")
    jars = sorted(directory.glob("*.jar")) if directory.is_dir() else []
    if not jars:
        fail("未找到 Compose 编译依赖，请运行 tools/prepare_compose.py 或用 --compose-dir 指定")
    runtime = [jar for jar in jars if zip_has(jar, "androidx/compose/runtime/SnapshotStateKt.class")
               or zip_has(jar, "androidx/compose/runtime/RuntimeKt.class")]
    if not runtime:
        fail("Compose runtime 缺失：需要真实 androidx.compose.runtime jar，而不是空的 JetBrains 转发包")
    return jars


def zip_has(jar: Path, entry: str) -> bool:
    try:
        with zipfile.ZipFile(jar) as bundle:
            return entry in bundle.namelist()
    except zipfile.BadZipFile:
        return False


def host_jar(args: argparse.Namespace) -> Path:
    if args.host_jar:
        jar = args.host_jar.resolve()
        if not jar.is_file():
            fail(f"宿主 jar 不存在: {jar}")
        return jar
    if not args.spw_dir:
        fail("请用 --spw-dir 指定 Salt Player 安装目录，或用 --host-jar 直接指定宿主 jar")
    source = args.spw_dir / "app" / "ffmpeg-x64.dll"
    if not source.is_file():
        fail(f"未找到宿主文件: {source}")
    TOOLS.mkdir(parents=True, exist_ok=True)
    target = TOOLS / "spw-host.jar"
    if not target.exists() or target.stat().st_size != source.stat().st_size:
        print(f"复制宿主编译类路径: {source}")
        shutil.copyfile(source, target)
    return target


def run(command: list[str], env: dict[str, str] | None = None) -> None:
    completed = subprocess.run(command, env=env, capture_output=True, text=True,
                               encoding="utf-8", errors="replace")
    if completed.stdout.strip():
        print(completed.stdout.rstrip())
    if completed.stderr.strip():
        print(completed.stderr.rstrip(), file=sys.stderr)
    if completed.returncode != 0:
        fail(f"命令失败 ({completed.returncode}): {command[0]}")


def compile_kotlin(jdk: Path, kotlin_home: Path, host: Path, jars: list[Path]) -> None:
    if CLASSES.exists():
        shutil.rmtree(CLASSES)
    CLASSES.mkdir(parents=True)
    sources = sorted(str(path) for path in KOTLIN_SOURCES.rglob("*.kt"))
    if not sources:
        fail("没有 Kotlin 源码")
    # The compiler's own stdlib goes first: the host jar also contains a Kotlin
    # stdlib, and inline functions compiled against it trip the inliner.
    classpath = [str(kotlin_home / "lib" / "kotlin-stdlib.jar")]
    classpath += [str(jar) for jar in jars]
    classpath.append(str(host))
    command = [
        str(jdk / "bin" / "java.exe"),
        "-cp", str(kotlin_home / "lib" / "*"),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib",
        "-classpath", os.pathsep.join(classpath),
        f"-Xplugin={kotlin_home / 'lib' / 'compose-compiler-plugin.jar'}",
        f"-Xjava-source-roots={JAVA_SOURCES}",
        "-jvm-target", "25",
        "-module-name", "salt-android-playlist-import",
        "-d", str(CLASSES),
        *sources,
    ]
    print(f"编译 {len(sources)} 个 Kotlin 文件")
    environment = os.environ.copy()
    environment["JAVA_HOME"] = str(jdk)
    run(command, environment)


def compile_java(jdk: Path, kotlin_home: Path, host: Path, jars: list[Path]) -> None:
    sources = sorted(str(path) for path in JAVA_SOURCES.rglob("*.java"))
    if not sources:
        fail("没有 Java 源码")
    classpath = [str(CLASSES), str(kotlin_home / "lib" / "kotlin-stdlib.jar")]
    classpath += [str(jar) for jar in jars]
    classpath.append(str(host))
    command = [
        str(jdk / "bin" / "javac.exe"),
        "--release", "25",
        "-encoding", "UTF-8",
        "-classpath", os.pathsep.join(classpath),
        "-d", str(CLASSES),
        *sources,
    ]
    print(f"编译 {len(sources)} 个 Java 文件")
    run(command)


def copy_resources() -> None:
    for resource in RESOURCES.rglob("*"):
        if resource.is_file():
            target = CLASSES / resource.relative_to(RESOURCES)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(resource, target)
    license_file = ROOT / "LICENSE"
    if license_file.is_file():
        (CLASSES / "META-INF").mkdir(parents=True, exist_ok=True)
        shutil.copyfile(license_file, CLASSES / "META-INF" / "LICENSE")


def wrap_manifest_line(line: str, limit: int = 70) -> list[str]:
    if len(line.encode("utf-8")) <= limit:
        return [line]
    lines: list[str] = []
    current = ""
    for character in line:
        candidate = current + character
        if len(candidate.encode("utf-8")) > limit:
            lines.append(current)
            current = " " + character
        else:
            current = candidate
    lines.append(current)
    return lines


def manifest_text() -> str:
    entries = [
        ("Manifest-Version", "1.0"),
        ("Plugin-Class", PLUGIN_CLASS),
        ("Plugin-Id", PLUGIN_ID),
        ("Plugin-Version", PLUGIN_VERSION),
        ("Plugin-Provider", "Local"),
        ("Plugin-Name", PLUGIN_NAME),
        ("Plugin-Description", PLUGIN_DESCRIPTION),
        ("Plugin-Has-Config", "true"),
        ("Plugin-Open-Source-Url", PLUGIN_URL),
        ("Plugin-Permissions", "library-read,class-transform"),
    ]
    lines: list[str] = []
    for key, value in entries:
        lines.extend(wrap_manifest_line(f"{key}: {value}"))
    return "\r\n".join(lines) + "\r\n\r\n"


def package_plugin() -> Path:
    manifest = CLASSES / "META-INF" / "MANIFEST.MF"
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(manifest_text(), encoding="utf-8", newline="")
    output = BUILD / "libs" / PACKAGE_FILENAME
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("classes/", "")
        archive.writestr("classes/META-INF/", "")
        for path in sorted(CLASSES.rglob("*")):
            if path.is_file():
                archive.write(path, "classes/" + path.relative_to(CLASSES).as_posix())
    print(f"打包 {output} ({output.stat().st_size} 字节)")
    return output


def install_tree(package: Path) -> Path:
    install = BUILD / "install" / PLUGIN_ID
    if install.exists():
        shutil.rmtree(install)
    install.mkdir(parents=True)
    with zipfile.ZipFile(package) as archive:
        archive.extractall(install)
    print(f"解包目录 {install}")
    return install


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--spw-dir", type=Path, default=None,
                        help="Salt Player for Windows 安装目录")
    parser.add_argument("--host-jar", type=Path, default=None,
                        help="直接使用已有的宿主 jar（默认从安装目录复制）")
    parser.add_argument("--kotlin-home", type=Path, default=None)
    parser.add_argument("--compose-dir", type=Path, default=None)
    parser.add_argument("--jdk", type=Path, default=None)
    parser.add_argument("--install-root", type=Path, default=None,
                        help="把解包后的插件复制到该 PF4J 插件目录")
    args = parser.parse_args()

    jdk = find_jdk(args.jdk)
    kotlin_home = find_kotlin(args.kotlin_home)
    jars = compose_jars(args.compose_dir)
    host = host_jar(args)
    print(f"JDK: {jdk}")
    print(f"Kotlin: {kotlin_home}")
    print(f"宿主: {host}")

    compile_kotlin(jdk, kotlin_home, host, jars)
    compile_java(jdk, kotlin_home, host, jars)
    copy_resources()
    package = package_plugin()
    install = install_tree(package)
    if args.install_root:
        target = args.install_root / PLUGIN_ID
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            # Keep backups outside the PF4J scan root, otherwise the backup
            # directory is discovered as a second plugin with the same ID.
            backup_root = args.install_root.parent / "plugin-backups"
            backup_root.mkdir(parents=True, exist_ok=True)
            backup = backup_root / f"{PLUGIN_ID}-{datetime.now():%Y%m%d-%H%M%S}"
            target.rename(backup)
            print(f"已备份旧插件到 {backup}")
        shutil.copytree(install, target)
        print(f"已安装到 {target}（需要重启播放器）")
    print(f"完成: {package}")


if __name__ == "__main__":
    main()
