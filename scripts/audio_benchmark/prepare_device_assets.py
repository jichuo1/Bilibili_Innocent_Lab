"""Verify original downloads and stage remote-file payloads for the optional device benchmark.

No downloads and no adb calls. Run using uv run --project scripts/audio_benchmark.
"""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "Bilibili_Innocent_Lab/app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/hook/feature/audio/assets/AudioAssetSpec.kt"


def prepare(model: Path, runtime: Path, destination: Path) -> None:
    # Read the six literal identities from the production spec, so tooling cannot silently use older pins.
    parts = re.findall(r'AudioAsset\("([^"\n]+)", (\d+), "([0-9a-f]{64})"(?:, "([^"\n]+)")?\)', SPEC.read_text(encoding="utf-8"))
    if len(parts) != 6:
        raise ValueError("Production asset-spec format changed; review this staging tool")
    destination.mkdir(parents=True, exist_ok=True)

    def verify(data: bytes, part: tuple) -> None:
        name, size, digest, _ = part
        if len(data) != int(size) or hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f"Wrong size or SHA-256: {name}")

    for file, part in zip((model, runtime), parts[:2], strict=True):
        verify(file.read_bytes(), part)
    staged = [(parts[0], model.read_bytes())]
    with zipfile.ZipFile(runtime) as archive:
        for part in parts[2:]:
            info = archive.getinfo(part[3])
            if info.file_size != int(part[1]):
                raise ValueError(f"Wrong archive entry size: {part[3]}")
            data = archive.read(info)
            verify(data, part)
            staged.append((part, data))
    for (name, _, digest, _), data in staged:
        (destination / f"audio-v1-{digest[:16]}-{name}").write_bytes(data)
    # Keep original filenames together for Android's document-picker offline import.
    offline = destination.parent / "offline-import"
    offline.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(model, offline / parts[0][0])
    shutil.copyfile(runtime, offline / parts[1][0])
    print(f"Verified {len(staged)} payloads in {destination}; original import files in {offline}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "captures/audio-assets/device")
    args = parser.parse_args()
    prepare(args.model, args.runtime, args.output)
