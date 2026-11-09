#!/usr/bin/env python3
"""Compile and run the host-side frame guard geometry test (no ncnn, no device).

The test pins frame_guard_geometry.h to the verbatim legacy per-frame implementation. It needs a
C++17 compiler: c++/g++/clang++ on PATH, or MSVC discovered through vswhere on Windows.
"""
import argparse
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "Bilibili_Innocent_Lab/app/src/main/cpp/player_interpolation/tests/frame_guard_geometry_test.cpp"
VSWHERE = Path(os.environ.get("ProgramFiles(x86)", "")) / "Microsoft Visual Studio/Installer/vswhere.exe"


def unix_compiler():
    for name in ("c++", "g++", "clang++"):
        found = shutil.which(name)
        if found:
            return found
    return None


def run_unix(compiler, output):
    binary = output / "frame_guard_geometry_test"
    subprocess.run([compiler, "-std=c++17", "-O2", "-Wall", "-Wextra", "-Wpedantic", "-o", str(binary), str(SOURCE)], check=True)
    return subprocess.run([str(binary)]).returncode


def run_msvc(output):
    if not VSWHERE.is_file():
        return None
    install = subprocess.run([str(VSWHERE), "-latest", "-products", "*", "-property", "installationPath"],
                             capture_output=True, text=True).stdout.strip()
    vcvars = Path(install) / "VC/Auxiliary/Build/vcvars64.bat"
    if not install or not vcvars.is_file():
        return None
    binary = output / "frame_guard_geometry_test.exe"
    script = output / "build_and_run.bat"
    lines = [
        "@echo off",
        f'call "{vcvars}" >nul 2>nul || exit /b 1',
        f'cl /nologo /std:c++17 /O2 /W4 /EHsc /Fo"{output}\\\\" /Fe"{binary}" "{SOURCE}" || exit /b 1',
        f'"{binary}"',
    ]
    script.write_text("\r\n".join(lines) + "\r\n")
    return subprocess.run(["cmd.exe", "/c", str(script)]).returncode


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", type=Path, help="keep build output in this directory")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as scratch:
        output = args.keep or Path(scratch)
        output.mkdir(parents=True, exist_ok=True)
        compiler = unix_compiler()
        code = run_unix(compiler, output) if compiler else run_msvc(output)
        if code is None:
            print("no C++17 compiler found (need c++/g++/clang++ or MSVC)", file=sys.stderr)
            return 2
        return code


if __name__ == "__main__":
    sys.exit(main())
