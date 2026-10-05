#!/usr/bin/env python3
"""CI sanity checks for the checked-in 100-item offline content catalog."""
import json
import wave
from collections import Counter
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "app/src/main/assets/asset-library"
manifest = json.loads((root / "library.json").read_text(encoding="utf-8"))
entries = manifest["entries"]
assert manifest["entryCount"] == 100 == len(entries), "catalog must have exactly 100 assets"
counts = Counter(item["category"] for item in entries)
assert counts == {
    "Characters": 20,
    "Objects": 20,
    "Backgrounds": 20,
    "Sounds": 16,
    "Behaviors": 12,
    "Shader Sources": 12,
}, counts
assert len({item["id"] for item in entries}) == 100, "asset IDs must be unique"
assert len({item["path"] for item in entries}) == 100, "asset paths must be unique"
for item in entries:
    path = Path(item["path"])
    assert not path.is_absolute() and ".." not in path.parts, item["path"]
    asset = root / path
    assert asset.is_file() and asset.stat().st_size > 8, f"missing/empty {asset}"
    assert item["license"] == "CC0-1.0"
    if asset.suffix == ".png":
        assert asset.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n", asset
    elif asset.suffix == ".wav":
        with wave.open(str(asset), "rb") as sound:
            assert sound.getnchannels() == 1 and sound.getframerate() == 22050 and sound.getnframes() > 0
    elif asset.suffix == ".js":
        assert "function" in asset.read_text(encoding="utf-8"), asset
    elif asset.suffix in {".frag", ".glsl", ".shader"}:
        assert "void main" in asset.read_text(encoding="utf-8"), asset
    else:
        raise AssertionError(f"unrecognized asset source type: {asset}")
print("Validated 100 original offline library entries:", dict(counts))
