#!/usr/bin/env python3
"""Compare STT engines on ai_buds finetune recordings (standard library only).

1) Copy the dataset from the phone:
     adb pull /sdcard/Download/ai_buds/finetune ./finetune

2) Run the recordings through server STT and write a review sheet:
     python3 tools/stt_eval.py run ./finetune \
         --engine qwen3=http://127.0.0.1:8081 [--engine nemotron=http://127.0.0.1:8082]
   -> ./finetune/review.tsv   columns: audio, ref, phone, qwen3, ...
      ref starts as a copy of the phone transcript; edit it into the correct sentence.
      Rows that already exist keep their ref; only missing engine columns are filled.

3) Score (only rows marked reviewed = rows whose ref you fixed, or set reviewed column to 1):
     python3 tools/stt_eval.py score ./finetune
   CER = character edit distance / reference length, ignoring whitespace and punctuation.
   --by mic   splits the scores by a metadata.jsonl field (mic = communication / recognition, stt = phone / server).
"""
import argparse
import base64
import csv
import glob
import json
import os
import sys
import time
import unicodedata
import urllib.request

REVIEW = "review.tsv"
FIXED = ["audio", "reviewed", "ref", "phone"]


def load_metadata(root):
    rows = {}
    for path in sorted(glob.glob(os.path.join(root, "metadata*.jsonl"))):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    m = json.loads(line)
                except json.JSONDecodeError:
                    continue
                rows[m["audio"]] = m
    return rows


def load_review(root):
    path = os.path.join(root, REVIEW)
    if not os.path.exists(path):
        return [], {}
    with open(path, encoding="utf-8", newline="") as f:
        r = csv.DictReader(f, delimiter="\t")
        return list(r.fieldnames or []), {row["audio"]: row for row in r}


def save_review(root, cols, rows):
    path = os.path.join(root, REVIEW)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols, delimiter="\t", extrasaction="ignore")
        w.writeheader()
        for key in sorted(rows):
            w.writerow({c: rows[key].get(c, "") for c in cols})
    os.replace(tmp, path)


def transcribe(url, wav, prompt, language, timeout):
    messages = [{"role": "user", "content": [
        {"type": "input_audio", "input_audio": {"data": base64.b64encode(wav).decode(), "format": "wav"}},
        {"type": "text", "text": prompt},
    ]}]
    if language:
        messages.append({"role": "assistant", "content": f"language {language}<asr_text>"})
    body = json.dumps({"messages": messages, "max_tokens": 512, "stream": False, "temperature": 0.0}).encode()
    req = urllib.request.Request(url.rstrip("/") + "/v1/chat/completions", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = json.load(r)["choices"][0]["message"].get("content") or ""
    tag = "<asr_text>"
    i = raw.find(tag)
    return (raw[i + len(tag):] if i >= 0 else raw).strip()


def one_line(s):
    return " ".join(s.split())


def cmd_run(a):
    meta = load_metadata(a.root)
    if not meta:
        sys.exit(f"no metadata*.jsonl in {a.root}")
    engines = []
    for e in a.engine:
        name, _, url = e.partition("=")
        if not url or name in FIXED:
            sys.exit(f"bad --engine {e!r} (use name=http://host:port)")
        engines.append((name, url))
    cols, rows = load_review(a.root)
    cols = cols or list(FIXED)
    for name, _ in engines:
        if name not in cols:
            cols.append(name)
    for key, m in meta.items():
        row = rows.setdefault(key, {"audio": key, "reviewed": "0"})
        row["phone"] = one_line(m.get("text", "")) if m.get("stt") == "phone" else row.get("phone", "")
        if not row.get("ref"):
            row["ref"] = one_line(m.get("text", ""))
    todo = [(k, n, u) for k in sorted(meta) for n, u in engines if not rows[k].get(n)]
    print(f"{len(meta)} recordings, {len(todo)} transcriptions to run")
    for i, (key, name, url) in enumerate(todo, 1):
        path = os.path.join(a.root, key)
        try:
            with open(path, "rb") as f:
                wav = f.read()
            t0 = time.time()
            text = one_line(transcribe(url, wav, a.prompt, a.language, a.timeout))
            print(f"[{i}/{len(todo)}] {name} {key} ({time.time() - t0:.1f}s): {text}")
        except Exception as ex:  # keep going; empty cell is retried next run
            print(f"[{i}/{len(todo)}] {name} {key} FAILED: {ex}", file=sys.stderr)
            continue
        rows[key][name] = text or "∅"
        if i % 10 == 0:
            save_review(a.root, cols, rows)
    save_review(a.root, cols, rows)
    print(f"wrote {os.path.join(a.root, REVIEW)} — fix the ref column and set reviewed=1, then run `score`")


def norm(s):
    s = unicodedata.normalize("NFC", s).lower()
    return [c for c in s if not (c.isspace() or unicodedata.category(c)[0] in "PS")]


def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def cmd_score(a):
    cols, rows = load_review(a.root)
    if not rows:
        sys.exit(f"no {REVIEW} in {a.root}; run `run` first")
    engines = [c for c in cols if c not in FIXED[:3]]
    done = [r for r in rows.values() if r.get("reviewed", "").strip() in ("1", "y", "yes", "o")]
    if not done:
        sys.exit("no rows with reviewed=1 yet")
    print(f"{len(done)} reviewed of {len(rows)}\n")
    groups = {"": done}
    if a.by:
        meta = load_metadata(a.root)
        groups = {}
        for r in done:
            groups.setdefault(str(meta.get(r["audio"], {}).get(a.by) or "?"), []).append(r)
    head = f"{a.by:<16}" if a.by else ""
    print(f"{head}{'engine':<12}{'CER':>8}{'exact':>8}{'n':>5}")
    for g in sorted(groups):
        for e in engines:
            errs = chars = exact = n = 0
            for r in groups[g]:
                hyp = r.get(e, "")
                if not hyp:
                    continue
                ref, h = norm(r["ref"]), norm("" if hyp == "∅" else hyp)
                errs += edit_distance(ref, h)
                chars += len(ref)
                exact += ref == h
                n += 1
            if n:
                lead = f"{g:<16}" if a.by else ""
                print(f"{lead}{e:<12}{errs / max(chars, 1):>8.2%}{exact / n:>8.0%}{n:>5}")
    if a.show:
        print()
        for r in sorted(done, key=lambda r: r["audio"]):
            print(r["audio"], "\n  ref  :", r["ref"])
            for e in engines:
                if r.get(e) and norm(r[e]) != norm(r["ref"]):
                    print(f"  {e:<5}:", r[e])


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("root")
    r.add_argument("--engine", action="append", default=[], help="name=http://host:port (llama-server)")
    r.add_argument("--prompt", default="Transcribe the audio.")
    r.add_argument("--language", default="Korean", help="'' for auto-detect")
    r.add_argument("--timeout", type=float, default=120)
    s = sub.add_parser("score")
    s.add_argument("root")
    s.add_argument("--show", action="store_true", help="print mismatching rows")
    s.add_argument("--by", default="", help="split scores by a metadata.jsonl field, e.g. mic or stt")
    a = p.parse_args()
    {"run": cmd_run, "score": cmd_score}[a.cmd](a)


if __name__ == "__main__":
    main()
