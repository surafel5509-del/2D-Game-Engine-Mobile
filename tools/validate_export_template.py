#!/usr/bin/env python3
"""Fail CI if the embedded standalone-player template is stale or ships editor screens."""
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/sengine"
ARCHIVE = ROOT / "app/src/main/assets/export-template.zip"
files = list((SRC / "engine").rglob("*.kt")) + [
    SRC / "project/Project.kt",
    SRC / "project/ProjectManager.kt",
    SRC / "ui/PlayerActivity.kt",
    SRC / "ui/GameControlsView.kt",
    SRC / "ui/Ui.kt",
]
expected = {f"app/src/main/java/{p.relative_to(ROOT / 'app/src/main/java').as_posix()}": p.read_bytes() for p in files}
with ZipFile(ARCHIVE) as archive:
    actual = {name: archive.read(name) for name in archive.namelist() if not name.endswith('/')}
assert set(actual) == set(expected), f"template file list differs: missing={set(expected)-set(actual)}, extra={set(actual)-set(expected)}"
for path, contents in expected.items():
    assert actual[path] == contents, f"stale exported runtime source: {path}; run tools/create_export_template.py"
assert not any("EditorActivity.kt" in path or "ProjectsActivity.kt" in path for path in actual)
print(f"Validated {len(actual)} up-to-date player-only Kotlin sources in export template")
