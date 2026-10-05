#!/usr/bin/env python3
"""Pack the small, player-only Kotlin source template into app assets.

The generated Android Studio export project gets its runtime from these current
engine sources; the editor, asset catalog, and project manager screens are not
shipped in per-game APKs.
"""
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/sengine"
DEST = ROOT / "app/src/main/assets/export-template.zip"

files = []
for folder in (SRC / "engine",):
    files.extend(folder.rglob("*.kt"))
files.extend([
    SRC / "project/Project.kt",
    SRC / "project/ProjectManager.kt",
    SRC / "ui/PlayerActivity.kt",
    SRC / "ui/GameControlsView.kt",
    SRC / "ui/Ui.kt",
])

with ZipFile(DEST, "w", ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(set(files)):
        rel = path.relative_to(ROOT / "app/src/main/java")
        archive.write(path, f"app/src/main/java/{rel.as_posix()}")
print(f"Packed {len(set(files))} current engine/player Kotlin sources into {DEST}")
