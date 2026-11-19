"""The desktop renderer uses the same ORT version as the Android dependency."""
import hashlib
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / "captures/audio-benchmark/onnxruntime.jar"
URL = "https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.31.0/onnxruntime-1.31.0.jar"
SHA256 = "d3f903af31fe8f01300adb6b2b67297aa5bee7895909afc32e76ef29a3921149"

if not TARGET.exists():
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(URL, timeout=60) as response:
        data = response.read(64 * 1024 * 1024 + 1)
    if len(data) != 50942147 or hashlib.sha256(data).hexdigest() != SHA256:
        raise ValueError("Unexpected runtime artifact")
    TARGET.write_bytes(data)
if hashlib.sha256(TARGET.read_bytes()).hexdigest() != SHA256:
    raise ValueError("Cached runtime artifact differs from the fixed version")
print(TARGET)
