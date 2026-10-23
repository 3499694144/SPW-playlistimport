#!/usr/bin/env python3
"""Dump the SPW library to a TSV file for tools/MatcherCheck.java.

The database stores a raw VRI (``file:///C:/...``); the Workshop Library API
exposes a platform path, so the prefix is stripped here to reproduce the
plugin's runtime view.

Usage:
    python tools/dump_library.py "%APPDATA%\\Salt Player for Windows\\spw.db" library.tsv
"""

from __future__ import annotations

import sqlite3
import sys
from pathlib import Path


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("usage: dump_library.py spw.db [library.tsv]")
    database = Path(sys.argv[1])
    output = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("library.tsv")
    connection = sqlite3.connect(f"file:{database.as_posix()}?mode=ro", uri=True)
    rows = connection.execute("select id, path, readable, title, artist from Track").fetchall()
    connection.close()
    lines: list[str] = []
    for track_id, path, readable, title, artist in rows:
        path = path or ""
        if path.startswith("file:///"):
            path = path[len("file:///"):]
        fields = [track_id or "", path, "true" if readable else "false", title or "", artist or ""]
        # TSV cannot carry stray tabs/newlines; these are never legitimate in a path.
        fields = [field.replace("\t", " ").replace("\r", " ").replace("\n", " ") for field in fields]
        lines.append("\t".join(fields))
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {output} ({len(lines)} tracks)")


if __name__ == "__main__":
    main()
