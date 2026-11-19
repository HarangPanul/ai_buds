#!/usr/bin/env python3
"""Check the quality of ai_buds recordings (standard library only).

    python3 tools/audio_check.py ./finetune            # per-file table + summary by mic mode
    python3 tools/audio_check.py ./finetune --summary  # summary only

Columns
  dur      seconds
  peak     peak level, dBFS (0 = full scale). clip = share of samples at full scale.
  speech   loud-frame level (90th percentile of 20 ms frame RMS), dBFS. Below about -35 is quiet.
  floor    quiet-frame level (10th percentile), dBFS = background noise / processing floor.
  snr      speech - floor, dB. Under 15 dB is noisy.
  hf       energy in 4.5-7 kHz relative to 0-4 kHz, dB, over loud frames.
           Wideband (16 kHz path, mSBC or better) usually sits around -15..-35.
           Under about -45 means nothing above 4 kHz: an 8 kHz narrowband path (CVSD) upsampled.
  cut      H = loud at the very start (first word may be clipped), T = loud at the very end
           (speech may have been cut before it finished).

Groups in the summary come from metadata.jsonl fields "mic" (communication / recognition) and "stt".
"""
import argparse
import cmath
import glob
import json
import math
import os
import struct
import sys
import wave

FRAME = 320          # 20 ms at 16 kHz
NFFT = 512


def read_wav(path):
    with wave.open(path, "rb") as w:
        if w.getsampwidth() != 2:
            raise ValueError("not 16-bit PCM")
        rate, ch, n = w.getframerate(), w.getnchannels(), w.getnframes()
        raw = w.readframes(n)
    s = struct.unpack(f"<{len(raw) // 2}h", raw)
    if ch > 1:
        s = s[::ch]
    return rate, s


def db(x):
    return 20 * math.log10(max(x, 1e-9))


def fft(x):
    n = len(x)
    if n == 1:
        return list(x)
    even, odd = fft(x[0::2]), fft(x[1::2])
    out = [0j] * n
    for k in range(n // 2):
        t = cmath.exp(-2j * math.pi * k / n) * odd[k]
        out[k], out[k + n // 2] = even[k] + t, even[k] - t
    return out


HANN = [0.5 - 0.5 * math.cos(2 * math.pi * i / NFFT) for i in range(NFFT)]


def hf_ratio(s, rate, loud_starts):
    """Energy in 4.5-7 kHz vs 0-4 kHz over loud frames (at most 60 of them).
    4.0-4.5 kHz is skipped: an 8 kHz path upsampled to 16 kHz still leaks there through the resampler's transition band."""
    if not loud_starts:
        return None
    step = max(1, len(loud_starts) // 60)
    b4, b45, b7 = (int(f * NFFT / rate) for f in (4000, 4500, 7000))
    lo = hi = 0.0
    for st in loud_starts[::step]:
        seg = s[st:st + NFFT]
        if len(seg) < NFFT:
            continue
        spec = fft([v * h for v, h in zip(seg, HANN)])
        p = [abs(c) ** 2 for c in spec[1:NFFT // 2]]
        lo += sum(p[:b4 - 1])
        hi += sum(p[b45 - 1:b7 - 1])
    if lo <= 0:
        return None
    return 10 * math.log10(max(hi, 1e-12) / lo)


def analyze(path):
    rate, s = read_wav(path)
    n = len(s)
    if n < FRAME * 5:
        return None
    full = 32767
    peak = max(abs(v) for v in s)
    clip = sum(1 for v in s if abs(v) >= full - 1) / n
    rms = []
    for i in range(0, n - FRAME + 1, FRAME):
        seg = s[i:i + FRAME]
        rms.append(math.sqrt(sum(v * v for v in seg) / FRAME))
    srt = sorted(rms)
    floor = srt[int(len(srt) * 0.10)]
    speech = srt[int(len(srt) * 0.90)]
    thresh = max(floor * 4, speech * 0.25)  # "loud" = well above the floor
    loud = [i * FRAME for i, r in enumerate(rms) if r >= thresh]
    edge = max(1, 100 // 20)  # first/last 100 ms
    head = max(rms[:edge]) >= thresh
    tail = max(rms[-edge:]) >= thresh
    return {
        "dur": n / rate,
        "peak": db(peak / full),
        "clip": clip,
        "speech": db(speech / full),
        "floor": db(floor / full),
        "snr": db(speech / full) - db(floor / full),
        "hf": hf_ratio(s, rate, loud),
        "cut": ("H" if head else "") + ("T" if tail else ""),
        "rate": rate,
    }


def load_meta(root):
    meta = {}
    for p in sorted(glob.glob(os.path.join(root, "metadata*.jsonl"))):
        with open(p, encoding="utf-8") as f:
            for line in f:
                try:
                    m = json.loads(line)
                    meta[m["audio"]] = m
                except (json.JSONDecodeError, KeyError):
                    pass
    return meta


def fmt(v, spec):
    return "   -" if v is None else format(v, spec)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("root", help="finetune folder (with metadata.jsonl and audio/) or a folder of wavs")
    p.add_argument("--summary", action="store_true", help="only print the per-group summary")
    a = p.parse_args()

    meta = load_meta(a.root)
    files = sorted(meta) if meta else sorted(os.path.relpath(f, a.root) for f in glob.glob(os.path.join(a.root, "**", "*.wav"), recursive=True))
    if not files:
        sys.exit(f"no recordings in {a.root}")

    groups = {}
    if not a.summary:
        print(f"{'file':<34}{'mic':<14}{'dur':>5}{'peak':>6}{'clip':>6}{'speech':>7}{'floor':>6}{'snr':>5}{'hf':>6} cut  text")
    for rel in files:
        path = os.path.join(a.root, rel)
        if not os.path.exists(path):
            continue
        try:
            r = analyze(path)
        except Exception as ex:
            print(f"{rel}: {ex}", file=sys.stderr)
            continue
        if r is None:
            continue
        m = meta.get(rel, {})
        key = (m.get("mic") or "?", m.get("stt") or "?")
        groups.setdefault(key, []).append((r, m.get("text", None)))
        if not a.summary:
            text = m.get("text")
            text = "(못 들음)" if text == "" else (text or "")
            print(f"{os.path.basename(rel):<34}{key[0]:<14}{r['dur']:>5.1f}{r['peak']:>6.0f}{r['clip']:>6.1%}"
                  f"{r['speech']:>7.0f}{r['floor']:>6.0f}{r['snr']:>5.0f}{fmt(r['hf'], '>6.0f')} {r['cut']:<4} {text[:30]}")

    print(f"\n{'mic':<14}{'stt':<8}{'n':>4}{'speech':>7}{'floor':>6}{'snr':>5}{'hf':>6}{'clip>0':>7}{'head':>6}{'tail':>6}{'못들음':>6}")
    for (mic, stt), rows in sorted(groups.items()):
        rs = [r for r, _ in rows]
        n = len(rs)

        def med(k):
            v = sorted(x[k] for x in rs if x[k] is not None)
            return v[len(v) // 2] if v else None
        print(f"{mic:<14}{stt:<8}{n:>4}{fmt(med('speech'), '>7.0f')}{fmt(med('floor'), '>6.0f')}{fmt(med('snr'), '>5.0f')}"
              f"{fmt(med('hf'), '>6.0f')}{sum(r['clip'] > 0 for r in rs) / n:>7.0%}"
              f"{sum('H' in r['cut'] for r in rs) / n:>6.0%}{sum('T' in r['cut'] for r in rs) / n:>6.0%}"
              f"{sum(t == '' for _, t in rows) / n:>6.0%}")
    hf = [r["hf"] for rows in groups.values() for r, _ in rows if r["hf"] is not None]
    if hf:
        med = sorted(hf)[len(hf) // 2]
        verdict = ("narrowband — 4 kHz 이상이 비어 있음: 8 kHz 통화 경로(CVSD)로 보임" if med < -45
                   else "wideband — 4 kHz 이상 성분 있음: 16 kHz 통화 경로(mSBC 이상)로 보임")
        print(f"\nbandwidth: median hf {med:.0f} dB → {verdict}")


if __name__ == "__main__":
    main()
