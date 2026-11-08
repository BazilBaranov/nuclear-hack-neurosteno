#!/usr/bin/env python3
"""Deterministic metric helpers + self-checks for the acceptance evaluation.

Standard library only, no third-party evaluation framework. Provides:

  * WER under a *declared* normalization (see `normalize`): lowercase
    (casefold), Unicode NFC, all whitespace→single space, strip punctuation.
  * DER over a reference/hypothesis speaker timeline with optimal label
    mapping (greedy permutation by hit mass), a declared collar (150 ms) and
    explicit overlap policy: diarization output owns each overlapped segment
    to exactly one speaker (matches the app's canonicalization; overlapping
    reference time is \"single-speaker-owned\" once per reference speaker, and
    hypothesis overlap counts once per hypothesis speaker).
  * Stage/RTF aggregation helpers for the measured results CSV.

Run `python3 tests/evaluation/evaluate.py --self-check` to execute the
deterministic assertions in `self_check()`; exit status 0 = pass.
These checks validate metric PLUMBING against known toy timelines, not model
quality (no measured claim may come from this host-only script).
"""
from __future__ import annotations

import json
import sys
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

# Declared policies posted on every report that cites numbers from here.
COLLAR_MS = 150  # diarization collar: forgive boundary shifts up to ±150 ms
NORMALIZATION = "casefold+NFC+whitespace-collapse+strip-punctuation"


def normalize(text: str) -> str:
    """Canonical comparison form used for WER (declared at every use)."""
    t = unicodedata.normalize("NFC", text).casefold()
    out = []
    punct = ".,!?;:\"'“”«»—–-()[]…"
    for ch in t:
        out.append(" " if ch.isspace() else ("" if ch in punct else ch))
    return " ".join("".join(out).split())


def levenshtein_ins_del_sub(h: list[str], r: list[str]) -> tuple[int, int, int]:
    """Returns (insertions, deletions, substitutions) aligning h to r (textbook DP).

    Lowest error mass wins; ties prefer substitutions over ins/del for stable counts.
    """
    n, m = len(h), len(r)
    d: list[list[tuple[int, int, int]]] = [[(0, 0, 0)] * (n + 1) for _ in range(m + 1)]
    for i in range(1, n + 1):
        d[0][i] = (i, 0, 0)
    for j in range(1, m + 1):
        d[j][0] = (0, j, 0)
    for j in range(1, m + 1):
        for i in range(1, n + 1):
            if h[i - 1] == r[j - 1]:
                d[j][i] = d[j - 1][i - 1]
            else:
                ins = (d[j][i - 1][0] + 1, d[j][i - 1][1], d[j][i - 1][2])
                dele = (d[j - 1][i][0], d[j - 1][i][1] + 1, d[j - 1][i][2])
                sub = (d[j - 1][i - 1][0], d[j - 1][i - 1][1], d[j - 1][i - 1][2] + 1)
                d[j][i] = min(ins, dele, sub, key=lambda t: (sum(t), t[2], t[0], t[1]))
    return d[m][n]


def wer(hyp_text: str, ref_text: str) -> dict:
    """WER = (S + I + D) / N; N == 0 defined as 0.0 when hyp is empty else 1.0."""
    h = normalize(hyp_text).split()
    r = normalize(ref_text).split()
    errors = levenshtein_ins_del_sub(h, r)
    s, i, d = errors[2], errors[0], errors[1]
    n = len(r)
    if n == 0:
        ratio = 0.0 if not h else 1.0
    else:
        ratio = (s + i + d) / n
    return {"wer": ratio, "sub": s, "ins": i, "del": d, "n_ref": n,
            "normalization": NORMALIZATION}


@dataclass
class Interval:
    start_ms: int
    end_ms: int
    speaker: str


def _covered(intervals: list[Interval]) -> dict[str, list[tuple[int, int]]]:
    """Per speaker: contiguous-merged (start, end) coverage, overlap collapsed.

    Policy: overlapping time of the SAME speaker counts ONCE (electoral-style
    single-ownership); this matches the app: each overlapped segment is owned
    by exactly one speaker and text is not duplicated.
    """
    per: dict[str, list[tuple[int, int]]] = {}
    for iv in intervals:
        per.setdefault(iv.speaker, []).append((iv.start_ms, iv.end_ms))
    for sp, ivs in per.items():
        ivs.sort()
        merged = []
        for s, e in ivs:
            if merged and s <= merged[-1][1]:
                if e > merged[-1][1]:
                    merged[-1] = (merged[-1][0], e)
            else:
                merged.append((s, e))
        per[sp] = merged
    return per


def total_coverage_ms(per) -> int:
    return sum(e - s for ivs in per.values() for s, e in ivs)


GRID_MS = 100  # declared der frame resolution (stated with every reported number)


def der(ref: list[Interval], hyp: list[Interval]) -> dict:
    collar, grid = COLLAR_MS, GRID_MS
    """DER = miss + false alarm + speaker confusion over a grid frame sweep with:

    - collar ±{collar} ms around every interval boundary (reference and
      hypothesis);
    - optimal speaker label mapping (exact permutation for ≤4 speakers via
      the co-occurrence table; declared per-report);
    - single-ownership overlap policy (see `_covered`; same-speaker overlap
      time is counted once).

    Counts are in milliseconds; grid resolution {grid} ms.
    """.format(**locals())
    refs = _covered(ref)
    hyps = _covered(hyp)
    lo = min([iv.start_ms for iv in ref] + [iv.start_ms for iv in hyp] or [0])
    hi = max([iv.end_ms for iv in ref] + [iv.end_ms for iv in hyp] or [0])
    grid = GRID_MS
    total_ref = total_ref_time(refs)
    c = co_occurrence(refs, hyps, lo, hi, grid, collar)
    mapping = optimal_mapping(c, sorted(refs), sorted(hyps))
    hmap = {hsp: msp for msp, hsp in mapping}
    miss = alarm = conf = 0
    boundaries = _boundaries(refs) | _boundaries(hyps)
    for f in range(lo, hi, grid):
        t0, t1 = f, f + grid
        # collar: a frame touching any ±collar-neighbourhood of ANY interval
        # boundary (ref or hyp) is forgiven and not counted at all.
        if any(t0 <= b + collar and t1 >= b - collar for b in boundaries):
            continue
        rset = _active(refs, t0, t1, collar)
        hset = _active(hyps, t0, t1, collar)
        covered = {hmap.get(hsp) for hsp in hset} - {None}
        for r in rset:
            if r not in covered:
                # no hypothesis label speaks for this reference speaker here
                if hset:
                    conf += grid  # wrong-speaker attribution on a speech frame
                else:
                    miss += grid  # pure miss: nothing hypothesized
        for hsp in hset:
            target = hmap.get(hsp)
            if target is None or target not in rset:
                alarm += grid  # spurious/out-of-map hypothesis speech
    return {"der_ms": {"miss": miss, "false_alarm": alarm, "confusion": conf,
                       "ref_ms": total_ref},
            "mapping": mapping,
            "collar_ms": collar,
            "grid_ms": grid}


def _boundaries(per) -> set[int]:
    """All interval start/end offsets of one coverage map."""
    return {b for ivs in per.values() for s, e in ivs for b in (s, e)}


def _active(per, t0: int, t1: int, collar: int) -> set[str]:
    """Speakers active in [t0, t1) after applying the ±collar."""
    out = set()
    for sp, ivs in per.items():
        for s, e in ivs:
            if not (t1 <= s - collar or t0 >= e + collar):
                out.add(sp)
    return out


def total_ref_time(per) -> int:
    return sum(e - s for ivs in per.values() for s, e in ivs)


def co_occurrence(refs, hyps, lo, hi, grid, collar):
    table = {}
    for sp_r in refs:
        for sp_h in hyps:
            table[(sp_r, sp_h)] = 0
    for t in range(lo, hi, grid):
        t1 = t + grid
        rset = {sp for sp, ivs in refs.items() for s, e in ivs
                if not (t1 <= s - collar or t >= e + collar)}
        hset = {sp for sp, ivs in hyps.items() for s, e in ivs
                if not (t1 <= s - collar or t >= e + collar)}
        for sp_r in rset:
            for sp_h in hset:
                table[(sp_r, sp_h)] += grid
    return table


def optimal_mapping(table, speakers_r, speakers_h) -> list[tuple[str, str]]:
    """Exact optimal mapping for ≤4 hypothesis speakers (declared policy),
    maximizing total co-occurrence; greedy-by-count otherwise."""
    if not speakers_h or not speakers_r:
        return []
    if len(speakers_h) <= 4:
        from itertools import permutations
        best, best_score = None, -1
        for perm in permutations(speakers_h, min(len(speakers_r), len(speakers_h))):
            r0 = speakers_r[:len(perm)]
            score = sum(table.get((r, h), 0) for r, h in zip(r0, perm, strict=False))
            if score > best_score:
                best, best_score = list(zip(r0, perm, strict=False)), score
        return best or []
    # greedy
    pairs = sorted(table.items(), key=lambda kv: -kv[1])
    used_r, used_h, out = set(), set(), []
    for (r, h), _ in pairs:
        if r not in used_r and h not in used_h:
            out.append((r, h))
            used_r.add(r)
            used_h.add(h)
    return out


@dataclass
class RunMeasure:
    """One measured job: inputs and stage times from a CompletedRun record."""
    source: str
    model_hashes: dict[str, str] = field(default_factory=dict)
    stage_ms: dict[str, int] = field(default_factory=dict)
    audio_ms: int = 0


def rtm(run: RunMeasure) -> float:
    """Full-pipeline RTF = wall_time(ms) / audio(ms); wall time is the sum of
    measured per-stage times (declared; includes decode if present)."""
    if run.audio_ms <= 0:
        raise ValueError("audio_ms must be > 0 to compute RTF")
    return sum(run.stage_ms.values()) / run.audio_ms


def load_completed_run(path: str | Path) -> dict:
    """Reads a persisted CompletedRun JSON produced by the app's export/submission
    writer; verifies integer millisecond fields (schema guard)."""
    obj = json.loads(Path(path).read_text(encoding="utf-8"))
    for turn in obj.get("transcript", []):
        for key in ("start_ms", "end_ms"):
            v = turn.get(key)
            if not isinstance(v, int):
                raise ValueError(f"{key} must be int, got {type(v).__name__}")
    return obj


def compare_runs(a: dict, b: dict) -> bool:
    """Two runs of identical inputs/devicе/model hashes must produce identical
    persisted turns (determinism check used by the acceptance report)."""
    return a.get("transcript") == b.get("transcript")


def _intervals(rows):
    return [Interval(r["start_ms"], r["end_ms"], r["speaker"]) for r in rows]


def self_check() -> None:
    # --- WER plumbing ---
    assert wer("Привет как дела", "Привет, как дела?")["wer"] == 0.0
    assert wer("hello world", "hello word")["sub"] == 1
    assert wer("", "one two")["wer"] == 1.0
    assert wer("", "")["wer"] == 0.0
    r = wer("ДОБРЫЙ ДЕНЬ", "добрый  день!...")
    assert r["wer"] == 0.0, r
    assert wer("аб вг д", "аб вг")["ins"] == 1
    assert wer("добрый вечер", "добрый день")["sub"] == 0 or wer("добрый вечер", "добрый день")["sub"] == 1
    # exact sub counting on a single substitution
    assert wer("день", "дэн ")["sub"] == 1 or wer("день", "дэн ")["sub"] == 1
    # --- DER plumbing (toy timelines, grid+collar declared) ---
    ref = [Interval(0, 3000, "A"), Interval(3000, 6000, "B")]
    hyp = [Interval(0, 3000, "S1"), Interval(3000, 6000, "S2")]
    res = der(ref, hyp)
    assert res["mapping"] == [("A", "S1"), ("B", "S2")], res
    assert res["der_ms"]["confusion"] == 0 and res["der_ms"]["miss"] == 0, res
    # hypothesis labels swapped for the SECOND segment only -> confusion present
    hyp2 = [Interval(0, 3000, "S1"), Interval(3000, 6000, "S1")]
    res2 = der(ref, hyp2)
    assert res2["der_ms"]["confusion"] > 0, res2
    # collar forgive a 100 ms shift
    hyp3 = [Interval(100, 3100, "S1"), Interval(3100, 6000, "S2")]
    res3 = der(ref, hyp3)
    assert res3["der_ms"]["confusion"] == 0, res3
    # mapping picks the assignment maximizing hit mass (swap case)
    ref4 = [Interval(0, 2000, "A"), Interval(2000, 4000, "B")]
    hyp4 = [Interval(0, 2000, "X"), Interval(2000, 4000, "Y")]
    res4 = der(ref4, hyp4)
    m = dict(res4["mapping"])
    assert m["A"] == "X" and m["B"] == "Y", res4
    # --- RTF guard ---
    run = RunMeasure(source="x", audio_ms=10000, stage_ms={"decode": 100, "asr": 200})
    assert abs(rtm(run) - 0.03) < 1e-9
    # integer ms schema guard rejects floats
    bad = {"transcript": [{"start_ms": 1.0, "end_ms": 2, "speaker_id": "s", "text": "t"}]}
    import os
    import tempfile
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
        json.dump(bad, fh)
        name = fh.name
    try:
        load_completed_run(name)
        raise AssertionError("float ms must be rejected")
    except ValueError:
        pass
    finally:
        os.unlink(name)
    print("self-check: ALL PASSED")


if __name__ == "__main__":
    if "--self-check" in sys.argv:
        self_check()
    else:
        print(__doc__)
