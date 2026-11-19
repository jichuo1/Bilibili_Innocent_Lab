"""Deterministic source-separated fixtures, production-DSP A/Bs, and reference metrics."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import time

import numpy as np
import onnxruntime as ort
from pystoi import stoi
from scipy.signal import correlate, resample_poly
import soundfile as sf

ROOT = Path(__file__).resolve().parents[2]
HOME = ROOT / "captures/audio-benchmark"
RATE = 48000


def rms(x):
    return float(np.sqrt(np.mean(np.asarray(x, dtype=np.float64) ** 2) + 1e-20))


def read(path, rate=RATE):
    x, sr = sf.read(path, dtype="float32", always_2d=True)
    divisor = math.gcd(sr, rate)
    return resample_poly(x, rate // divisor, sr // divisor, axis=0).astype(np.float32)


def generate():
    sources = HOME / "sources"
    target = HOME / "fixtures"
    target.mkdir(exist_ok=True)
    music = read(sources / "music.ogg").mean(axis=1)
    cases = []
    def add(name, speech, interference, config, note, kind="speech"):
        mix = speech + interference
        scale = min(1.0, .95 / max(float(np.max(np.abs(mix))), 1e-6))
        speech, interference, mix = [np.asarray(x * scale, dtype="<f4") for x in (speech, interference, mix)]
        mix.tofile(target / f"{name}.f32")
        reference = speech
        if kind == "channel":
            good_ear = speech.sum(axis=1)
            reference = np.column_stack((good_ear, good_ear))
        np.savez_compressed(target / f"{name}.npz", speech=reference, source_speech=speech, interference=interference)
        sf.write(target / f"{name}.wav", mix, RATE, subtype="PCM_16")
        sf.write(target / f"{name}-reference.wav", reference, RATE, subtype="PCM_16")
        cases.append(dict(name=name, config=config, note=note, kind=kind, frames=len(mix),
                          input_sha256=hashlib.sha256(mix.tobytes()).hexdigest()))

    for i, number in enumerate((0, 101, 102, "zh")):
        mono = read(sources / f"speech-{number}.wav").mean(axis=1)
        mono *= .045 / rms(mono)
        speech = np.column_stack((mono, mono))
        m = np.resize(music[i * RATE * 12:], len(mono))
        m *= rms(mono) / rms(m)
        centered = np.column_stack((m, m))
        wide = np.column_stack((m, np.roll(m, 1777)))
        add(f"clean-{number}", speech, speech * 0, [0, 0, 0, 1], "clean speech: do not damage")
        add(f"clean-guard-{number}", speech, speech * 0, [1, 0, 0, 0], "normal speech through guard only: preserve natural syllable crests")
        for snr in (5, 0, -5):
            add(f"center-bgm-{number}-{snr:+d}dB", speech, centered * 10 ** (-snr / 20), [1, 0, 0, 1], "fully centered instrumental BGM and centered speech")
            add(f"voice-only-center-{number}-{snr:+d}dB", speech, centered * 10 ** (-snr / 20), [0, 0, 0, 1], "isolate model from peak guard; same centered BGM mixture")
        add(f"wide-bgm-{number}", speech, wide, [1, 0, 0, 1], "constructed stereo BGM; same source and level as centered 0 dB")
        add(f"voice-only-wide-{number}", speech, wide, [0, 0, 0, 1], "isolate model from peak guard; same stereo BGM mixture")
        if i == 0:
            add("panned-voice-center-bgm", speech * [1, .25], centered, [1, 0, 0, 1], "speaker panned left; centered BGM")
            add("antiphase-voice", speech * [1, -1], speech * 0, [0, 0, 0, 1], "phase-inverted speech must not disappear")
            for side, mask in (("left", [1, 0]), ("right", [0, 1])):
                damaged = speech * mask
                add(f"missing-{side}", damaged, speech * 0, [0, 2, 0, 0], "auto repair: remaining good ear named in filename", "channel")
            rng = np.random.default_rng(20261024)
            white = rng.standard_normal(speech.shape).astype(np.float32)
            white *= rms(speech) / rms(white)
            t = np.arange(len(mono)) / RATE
            hum = sum(np.sin(2 * np.pi * hz * t) for hz in (50, 60, 100, 120))
            hum = np.column_stack((hum, hum)).astype(np.float32)
            hum *= .025 / rms(hum)
            add("mains-hum", speech, hum, [0, 0, 1, 0], "50/60/100/120 Hz mains interference")
            add("broadband-hiss", speech, white * 10 ** (-5 / 20), [0, 0, 1, 0], "stationary broadband noise at +5 dB speech/noise ratio")
            pause = speech.copy(); pause[RATE * 3:RATE * 4] = 0
            add("noise-in-pause", pause, white * .2 + hum * .4, [0, 0, 1, 0], "one-second pause with continuing noise")
            for duration, label in ((.025, "short"), (.5, "long")):
                burst = np.zeros_like(speech)
                start = RATE * 4; count = int(RATE * duration)
                b = np.sin(2 * np.pi * 1400 * np.arange(count) / RATE) * .85
                burst[start:start + count] = np.column_stack((b, b * .75))
                add(f"sudden-burst-{label}", speech, burst, [1, 0, 0, 0], "fixed 4 s abrupt burst; compare attack, ceiling, recovery", "peak")
    (target / "manifest.json").write_text(json.dumps(dict(rate=RATE, seed=20261024, cases=cases), ensure_ascii=False, indent=2), encoding="utf-8")
    (target / "index.tsv").write_text("name\tguard\tchannels\tnoise\tvoice\n" + "".join(c["name"] + "\t" + "\t".join(map(str, c["config"])) + "\n" for c in cases), encoding="utf-8")
    print(f"Generated {len(cases)} fixed cases at {target}")


def neural(x, session):
    """Official 512/256 sqrt-periodic-Hann ONNX recipe; offline evaluation only."""
    x16 = resample_poly(x, 1, 3, axis=0)
    window = np.sqrt(.5 - .5 * np.cos(2 * np.pi * np.arange(512) / 512)).astype(np.float32)
    result = np.empty_like(x16)
    for channel in range(x.shape[1]):
        raw = np.pad(x16[:, channel], (256, 512), mode="constant")
        out = np.zeros_like(raw); norm = np.zeros_like(raw)
        cache = [np.zeros(shape, dtype=np.float32) for shape in ((2, 1, 16, 16, 33), (2, 3, 1, 1, 16), (2, 1, 33, 16))]
        for at in range(0, len(raw) - 511, 256):
            spec = np.fft.rfft(raw[at:at + 512] * window)
            inp = np.stack((spec.real, spec.imag), axis=-1)[None, :, None, :].astype(np.float32)
            enhanced, *cache = session.run(None, dict(zip(("mix", "conv_cache", "tra_cache", "inter_cache"), [inp] + cache)))
            spec = enhanced[0, :, 0, 0] + 1j * enhanced[0, :, 0, 1]
            out[at:at + 512] += np.fft.irfft(spec, 512).astype(np.float32) * window
            norm[at:at + 512] += window * window
        result[:, channel] = (out / np.maximum(norm, 1e-8))[256:256 + len(x16)]
    return resample_poly(result, 3, 1, axis=0)[:len(x)].astype(np.float32)


def candidate():
    output = HOME / "candidate"; output.mkdir(exist_ok=True)
    options = ort.SessionOptions(); options.intra_op_num_threads = 1; options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(HOME / "sources/gtcrn.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
    for case in json.loads((HOME / "fixtures/manifest.json").read_text(encoding="utf-8"))["cases"]:
        if case["config"][2] or case["config"][3]:
            x = np.fromfile(HOME / "fixtures" / (case["name"] + ".f32"), dtype="<f4").reshape(-1, 2)
            start = time.perf_counter(); enhanced = neural(x, session)
            enhanced.tofile(output / (case["name"] + ".f32"))
            print(case["name"], "rtf", round((time.perf_counter() - start) / (len(x) / RATE), 3), flush=True)


def si_sdr(reference, output):
    r = reference.astype(np.float64).ravel(); y = output.astype(np.float64).ravel()
    r -= r.mean(); y -= y.mean()
    target = r * (np.dot(y, r) / max(np.dot(r, r), 1e-20))
    return float(10 * np.log10((np.dot(target, target) + 1e-20) / (np.dot(y - target, y - target) + 1e-20)))


def measure(label):
    directory = HOME / label
    delay = 0
    if label not in ("baseline", "candidate"):
        anchor = np.fromfile(directory / "clean-0.f32", dtype="<f4").reshape(-1, 2)[:, 0]
        original = np.fromfile(HOME / "fixtures/clean-0.f32", dtype="<f4").reshape(-1, 2)[:, 0]
        corr = correlate(anchor[:RATE * 5], original[:RATE * 5], mode="full", method="fft")
        start = RATE * 5 - 1
        delay = int(np.argmax(corr[start:start + int(RATE * .08)]))
        print("Measured fixed stream delay:", delay, "frames", delay * 1000 / RATE, "ms", flush=True)
    result = []
    for case in json.loads((HOME / "fixtures/manifest.json").read_text(encoding="utf-8"))["cases"]:
        name = case["name"]
        path = directory / f"{name}.f32"
        if not path.exists():
            continue
        x = np.fromfile(HOME / "fixtures" / f"{name}.f32", dtype="<f4").reshape(-1, 2)
        y = np.fromfile(path, dtype="<f4").reshape(-1, 2)
        assert y.shape == x.shape and np.isfinite(y).all()
        ref = np.load(HOME / "fixtures" / f"{name}.npz")["speech"]
        shift = delay if case["config"][2] or case["config"][3] else 0
        # Use the identical source-time interval for baseline and streaming versions.
        # 1582 frames is the measured 48 kHz production delay, not a fitted per-case shift.
        tail = max(delay, 1582) if case["config"][2] or case["config"][3] else 0
        length = len(x) - tail
        aligned = y[shift:shift + length]
        reference = ref[:len(aligned)]
        row = dict(name=name, input_peak=float(np.max(np.abs(x))), output_peak=float(np.max(np.abs(y))),
                   in_si_sdr=si_sdr(reference, x[:len(aligned)]), out_si_sdr=si_sdr(reference, aligned), rms_gain_db=20 * np.log10(rms(y) / rms(x)), alignment_frames=shift, evaluated_frames=len(aligned))
        if case["kind"] == "speech":
            # Per-channel STOI preserves antiphase and off-centre speech; no stereo averaging.
            row["in_stoi"] = float(np.mean([stoi(reference[:, c], x[:len(aligned), c], RATE) for c in range(2)]))
            row["out_stoi"] = float(np.mean([stoi(reference[:, c], aligned[:, c], RATE) for c in range(2)]))
        if case["kind"] == "channel":
            row["ear_difference_rms"] = rms(y[RATE:, 0] - y[RATE:, 1])
        if case["kind"] == "peak":
            row["burst_reduction_db"] = 20 * np.log10(rms(y[RATE * 4:RATE * 4 + 1000]) / rms(x[RATE * 4:RATE * 4 + 1000]))
            row["pre_burst_error_rms"] = rms(y[:RATE * 3] - x[:RATE * 3])
        sf.write(directory / f"{name}.wav", y, RATE, subtype="PCM_16")
        # A then B, separated by 0.7 s; all previews share a fixed -12 dB attenuation.
        ab = np.concatenate((x[:len(aligned)], np.zeros((int(RATE * .7), 2)), aligned)) * .25
        sf.write(directory / f"{name}-AB.wav", ab, RATE, subtype="PCM_16")
        result.append(row)
        print(name, "SI-SDR", round(row["in_si_sdr"], 2), "->", round(row["out_si_sdr"], 2), "STOI", round(row.get("out_stoi", 0), 3), flush=True)
    (directory / "metrics.json").write_text(json.dumps(result, indent=2), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("generate", "candidate", "measure"))
    parser.add_argument("label", nargs="?", default="baseline")
    args = parser.parse_args()
    {"generate": generate, "candidate": candidate, "measure": lambda: measure(args.label)}[args.command]()
