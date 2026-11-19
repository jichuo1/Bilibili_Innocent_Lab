"""Build an offline listening page. Preview gains are fixed and explicitly reported."""
from __future__ import annotations

import html
import json
from pathlib import Path

import numpy as np
import soundfile as sf

from benchmark import HOME, RATE


def main():
    cases = json.loads((HOME / "fixtures/manifest.json").read_text(encoding="utf-8"))["cases"]
    old = {r["name"]: r for r in json.loads((HOME / "baseline/metrics.json").read_text())}
    new = {r["name"]: r for r in json.loads((HOME / "revised/metrics.json").read_text())}
    device_path = HOME / "device/metrics.json"
    device = {r["name"]: r for r in json.loads(device_path.read_text())} if device_path.exists() else {}
    listening = HOME / "listening"; listening.mkdir(exist_ok=True)
    for case in cases:
        name = case["name"]
        original = np.fromfile(HOME / "fixtures" / f"{name}.f32", dtype="<f4").reshape(-1, 2)
        reference = np.load(HOME / "fixtures" / f"{name}.npz")["speech"]
        versions = dict(original=original, reference=reference)
        for label in ("baseline", "revised", "device"):
            if label == "device" and name not in device:
                continue
            audio = np.fromfile(HOME / label / f"{name}.f32", dtype="<f4").reshape(-1, 2)
            shift = (device[name] if label == "device" else new[name])["alignment_frames"] if label != "baseline" else 0
            versions[label] = audio[shift:]
        length = min(map(len, versions.values()))
        reference = reference[:length]
        speech_energy = float(np.sum(reference.astype(np.float64) ** 2))
        input_projection = float(np.sum(original[:length] * reference)) / max(speech_energy, 1e-20)
        for label, audio in versions.items():
            audio = audio[:length]
            # The same -12.04 dB preview attenuation for every version, never per-file peak normalisation.
            sf.write(listening / f"{name}-{label}.wav", audio * .25, RATE, subtype="PCM_16")
            projection = float(np.sum(audio * reference)) / max(speech_energy, 1e-20)
            match = float(np.clip(input_projection / max(projection, 1e-5), .5, 2)) if case["kind"] == "speech" else 1
            sf.write(listening / f"{name}-{label}-matched.wav", audio * .25 * match, RATE, subtype="PCM_16")
        processed = versions.get("device", versions["revised"])
        ab = np.concatenate((versions["original"][:length], np.zeros((int(RATE * .7), 2)), processed[:length])) * .25
        sf.write(listening / f"{name}-AB.wav", ab, RATE, subtype="PCM_16")
        case["old"] = old[name]; case["new"] = new[name]
        if name in device:
            case["device"] = device[name]
    dataset = json.dumps(cases, ensure_ascii=False).replace("<", "\\u003c")
    page = r'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>音频拯救固定测试集 · A/B</title><style>
body{font:16px/1.6 system-ui,sans-serif;background:#12151c;color:#edf0f6;max-width:1050px;margin:30px auto;padding:0 20px}h1{font-size:28px}small,.note{color:#aebbd1}button,input,select{font:inherit}button{background:#26354c;color:#fff;border:1px solid #456083;border-radius:8px;padding:7px 14px;margin:4px;cursor:pointer}button.selected{background:#3473ce}header{position:sticky;top:0;background:#12151cf5;padding:12px 0;z-index:1;border-bottom:1px solid #40506a}audio{width:100%}.case{border:1px solid #344157;border-radius:12px;margin:16px 0;padding:16px}.case h2{font-size:18px;margin:0}.stats{display:flex;gap:30px;flex-wrap:wrap;color:#b8d7ff}.note{font-size:14px}a{color:#9ac7ff}#search{padding:8px;background:#26354c;color:white;border:1px solid #456083;border-radius:7px;width:60%}</style>
<h1>音频拯救 · 固定 A/B 测试集</h1><p>49 个确定性样本，中英文真实讲话 + 独立音乐/噪声；全部使用相同混合配方比较。</p>
<p class="note">所有试听版统一衰减约 12 dB；没有逐文件峰值归一化。新版本对齐固定模型延迟，播放器实际延迟约 33 ms。音量匹配只用于语音效果比较，爆音样本保持固定增益。连续 A/B 优先使用一加实测输出。指标不能代替听感。</p>
<header><div id="now">选择样本，然后切换原始、旧版、修订版或干净参考。</div><audio id="player" controls preload="none"></audio><label><input id="matched" type="checkbox">匹配人声响度（基于干净参考的语音增益，最多 ±6 dB）</label><br><input id="search" placeholder="筛选：zh / center / missing / burst / hiss / clean"><span id="count"></span></header><main id="cases"></main>
<script>const data=DATA;const player=document.querySelector('#player');let current=null,version='original';
function choose(c,v){const keep=current?.name===c.name?player.currentTime:0;current=c;version=v;const suffix=document.querySelector('#matched').checked&&c.kind==='speech'?'-matched':'';player.src='listening/'+encodeURIComponent(c.name)+'-'+v+suffix+'.wav';player.onloadedmetadata=()=>{player.currentTime=Math.min(keep,Math.max(0,player.duration-.1));player.play().catch(()=>{})};document.querySelector('#now').textContent=c.name+' · '+({original:'原始',baseline:'旧版 c1c91b50',revised:'修订版（桌面）',device:'一加实测',reference:'干净参考'}[v]);document.querySelectorAll('button').forEach(b=>b.classList.toggle('selected',b.dataset.name===c.name&&b.dataset.version===v))}
function draw(){const search=document.querySelector('#search').value.toLowerCase();const root=document.querySelector('#cases');root.replaceChildren();let count=0;for(const c of data){if(!c.name.toLowerCase().includes(search))continue;count++;const e=document.createElement('section');e.className='case';const h=document.createElement('h2');h.textContent=c.name;e.append(h);const n=document.createElement('div');n.className='note';n.textContent=c.note;e.append(n);const stats=document.createElement('div');stats.className='stats';const m=c.device||c.new;if(m.out_stoi!==undefined)stats.textContent='STOI（'+(c.device?'一加':'桌面')+'）：原始 '+m.in_stoi.toFixed(3)+' / 旧版 '+c.old.out_stoi.toFixed(3)+' / 修订版 '+m.out_stoi.toFixed(3)+'　SI-SDR：'+m.in_si_sdr.toFixed(1)+' → '+m.out_si_sdr.toFixed(1)+' dB';else if(c.kind==='peak')stats.textContent='爆音前段衰减：'+m.burst_reduction_db.toFixed(1)+' dB；输出采样峰值：'+m.output_peak.toFixed(3);else stats.textContent='补耳后左右差 RMS：'+m.ear_difference_rms.toExponential(2);e.append(stats);for(const [v,title]of Object.entries({original:'原始',baseline:'旧版',revised:'修订版（桌面）',device:'一加实测',reference:'干净参考'})){if(v==='device'&&!c.device)continue;const b=document.createElement('button');b.textContent=title;b.dataset.name=c.name;b.dataset.version=v;b.onclick=()=>choose(c,v);e.append(b)}const a=document.createElement('a');a.href='listening/'+encodeURIComponent(c.name)+'-AB.wav';a.textContent='连续 A/B 文件';a.style.marginLeft='12px';e.append(a);root.append(e)}document.querySelector('#count').textContent='　'+count+' 项'}document.querySelector('#search').oninput=draw;document.querySelector('#matched').onchange=()=>{if(current)choose(current,version)};draw();</script></html>'''
    (HOME / "index.html").write_text(page.replace("DATA", dataset), encoding="utf-8")
    (HOME / "results.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2), encoding="utf-8")
    print(HOME / "index.html")


if __name__ == "__main__":
    main()
