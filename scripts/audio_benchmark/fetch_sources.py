"""Fetch a small, versioned evaluation corpus; never fetch entire training datasets."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "captures/audio-benchmark/sources"
OUT.mkdir(parents=True, exist_ok=True)
LOCK = Path(__file__).with_name("sources.lock.json")
EXPECTED = {record["name"]: record for record in json.loads(LOCK.read_text(encoding="utf-8"))} if LOCK.exists() else {}
GT = "502ebfab64da7c4a9af78dcb9c6ceef1ebb01c73"
DNS = "70f19285c36cca4df2338f9248775ddc50980c6b"


def fetch(url: str, path: Path) -> bytes:
    if path.exists():
        return path.read_bytes()
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=25) as response:
                content = response.read(8 * 1024 * 1024 + 1)
            if len(content) > 8 * 1024 * 1024:
                raise ValueError("Source exceeds the per-file download bound")
            path.write_bytes(content)
            return content
        except Exception:
            if attempt == 2:
                raise
            time.sleep(1 + attempt)
    raise AssertionError()


def main() -> None:
    records = []
    def item(name: str, url: str, license: str, role: str) -> None:
        data = fetch(url, OUT / name)
        if name.endswith(".wav") and data[:4] != b"RIFF":
            raise ValueError(f"Not a WAV: {name}")
        record = dict(name=name, url=url, license=license, role=role,
                      bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        if name in EXPECTED:
            expected = EXPECTED[name]
            if any(record[key] != expected[key] for key in ("url", "bytes", "sha256")):
                raise ValueError(f"Source differs from the checked-in lock: {name}")
        records.append(record)
        print(name, len(data), records[-1]["sha256"], flush=True)

    item("gtcrn.onnx", f"https://raw.githubusercontent.com/Xiaobin-Rong/gtcrn/{GT}/stream/onnx_models/gtcrn_simple.onnx", "MIT", "candidate model, DNS3 pretrained")
    item("gtcrn-LICENSE.txt", f"https://raw.githubusercontent.com/Xiaobin-Rong/gtcrn/{GT}/LICENSE", "MIT", "model and code license")
    item("dns-LICENSE.txt", f"https://raw.githubusercontent.com/microsoft/DNS-Challenge/{DNS}/LICENSE", "upstream dataset terms", "speech source license")
    # Use the Git LFS media endpoint, not a 131-byte pointer masquerading as audio.
    for number in (0, 101, 102):
        url = f"https://media.githubusercontent.com/media/microsoft/DNS-Challenge/{DNS}/datasets/test_set/synthetic/no_reverb/clean/clean_fileid_{number}.wav"
        try:
            item(f"speech-{number}.wav", url, "see dns-LICENSE.txt", "real held-out clean speech")
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            item(f"speech-{number}.wav", url.replace("/synthetic/", "/"), "see dns-LICENSE.txt", "real held-out clean speech")
    item("speech-zh.wav", "https://raw.githubusercontent.com/wenet-e2e/wenet/3b977aeabcd90f95e0a862babba88abae159f94a/test/resources/aishell-BAC009S0724W0121.wav", "AISHELL-1 / WeNet Apache 2.0; see source notices", "real Mandarin clean speech")
    item("wenet-LICENSE.txt", "https://raw.githubusercontent.com/wenet-e2e/wenet/3b977aeabcd90f95e0a862babba88abae159f94a/LICENSE", "Apache 2.0", "Mandarin fixture repository license")
    item("music.ogg", "https://librosa.org/data/audio/Kevin_MacLeod_-_Vibe_Ace.ogg", "CC BY 3.0, Kevin MacLeod, Vibe Ace", "instrumental BGM")
    (OUT / "sources.json").write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
