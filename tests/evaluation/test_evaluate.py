#!/usr/bin/env python3
"""Deterministic self-check tests for the evaluation metrics (stdlib only).

Run directly:  python3 tests/evaluation/test_evaluate.py
Exit status 0 = all checks pass.
"""
import hashlib
import json
import sys
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))

import evaluate as ev  # noqa: E402


def test_manifest_checksums():
    m = json.loads((HERE.parent / "fixtures" / "manifest.json").read_text(encoding="utf-8"))
    assert m["references"], "manifest must not be empty"
    for ref in m["references"]:
        p = HERE.parent / "fixtures" / ref["file"]
        assert p.is_file(), ref["file"]
        got = hashlib.sha256(p.read_bytes()).hexdigest()
        assert got == ref["checksum_sha256"], f"checksum mismatch for {ref['file']}"
        assert ref["consent_or_license"].startswith("CC0"), "explicit license required"
    # disjoint split: no file belongs to both sets
    purposedev = {r["file"] for r in m["references"] if r["split_purpose"] == "development"}
    purposefin = {r["file"] for r in m["references"] if r["split_purpose"] == "final-check"}
    assert purposedev and purposefin and not (purposedev & purposefin)
    # recurrence + structural cases present
    assert any(r["speaker_recurrence"] for r in m["references"])
    assert any(r["overlap_ms"] > 0 for r in m["references"])
    assert any(r["language"] == "unsupported" for r in m["references"])
    assert any(r["leading_silence_ms"] >= 2000 for r in m["references"])


def test_wer_metric():
    assert ev.wer("Привет как дела", "Привет, как дела?")["wer"] == 0.0
    r = ev.wer("hello world", "hello word")
    assert (r["sub"], r["del"], r["ins"]) == (1, 0, 0)
    assert ev.wer("", "a b")["wer"] == 1.0
    assert ev.wer("", "")["wer"] == 0.0


def test_der_metric():
    ref = [ev.Interval(0, 3000, "A"), ev.Interval(3000, 6000, "B")]
    res = ev.der(ref, [ev.Interval(0, 3000, "S1"), ev.Interval(3000, 6000, "S2")])
    assert res["der_ms"]["confusion"] == 0 and res["der_ms"]["miss"] == 0
    res2 = ev.der(ref, [ev.Interval(0, 6000, "S1")])
    assert res2["der_ms"]["confusion"] > 0, res2
    res3 = ev.der(ref, [ev.Interval(100, 3100, "S1"), ev.Interval(3100, 6000, "S2")])
    assert res3["der_ms"]["confusion"] == 0, res3


def test_rtf_and_schema():
    run = ev.RunMeasure(source="x", audio_ms=10000, stage_ms={"decode": 100, "asr": 200})
    assert abs(ev.rtm(run) - 0.03) < 1e-9
    try:
        ev.rtm(ev.RunMeasure(source="x", audio_ms=0, stage_ms={"a": 1}))
        raise AssertionError("audio_ms=0 must raise")
    except ValueError:
        pass


def main():
    for name in ("test_manifest_checksums", "test_wer_metric", "test_der_metric", "test_rtf_and_schema"):
        globals()[name]()
        print(name, "OK")
    print("ALL PASSED")


if __name__ == "__main__":
    main()
