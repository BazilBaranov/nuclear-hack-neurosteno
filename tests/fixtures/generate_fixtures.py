#!/usr/bin/env python3
"""Generates the deterministic synthetic fixture set under tests/fixtures.

These are OWN, synthetic stand-in recordings (mono 16 kHz PCM16 WAV): the team has
no license for real conversation speech and no real RU recording was made in time.
The waveforms carry per-speaker voiced hums, noise, silence and uncertain low-SNR
speech-like material on a known timeline, so they are usable for device decode /
import / timeline-persistence harnesses, deterministic metric self-checks against
known offsets, and speaker-recurrence / overlap regression checks.

They are NOT real speech: any ASR text produced on them is meaningless and MUST
NOT be reported as WER evidence (documented in docs/TESTING.md).

Deterministic: integer-only synthesis, no clock or hash()-based randomness, so a
re-run reproduces byte-identical WAVs (checksums in manifest.json / SHA256SUMS.txt).

Split purpose: dev_* files feed tuning; final_* files are a disjoint final check
set (different seeds, speakers and offsets) — never used to tune the pipeline.
"""
import hashlib
import json
import math
import struct
import wave
from pathlib import Path

SR = 16000
HERE = Path(__file__).parent


def idx2val(x: int, k: int) -> float:
    """Deterministic pseudo-random in [-1, 1) from integer state (LCG)."""
    v = (x * 1103515245 + 12345 * k + 7654321) & 0x7FFFFFFF
    return (v / 1073741823.5) - 1.0


def wav_write(path: Path, samples: list[float]) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        frames = b"".join(
            struct.pack("<h", max(-32768, min(32767, int(s)))) for s in samples
        )
        w.writeframes(frames)


def synth(segments: list[dict]) -> tuple[list[float], int]:
    """Materializes a timeline; `overlap` segments mix their named speakers.

    Returns (samples, overlap_ms); raises on unknown kinds or non-contiguous
    coverage so the manifest can never drift from the audio.
    """
    total = segments[-1]["end_ms"] * SR // 1000
    buf = [0.0] * total
    noise_state = 0
    overlap_ms = 0
    prev_end = 0
    for seg in segments:
        if seg["start_ms"] != prev_end:
            raise ValueError(f"segments must be contiguous: {seg}")
        prev_end = seg["end_ms"]
        if seg["kind"] == "silence":
            continue
        if seg["kind"] == "overlap":
            overlap_ms = max(overlap_ms, seg["end_ms"] - seg["start_ms"])
        start = seg["start_ms"] * SR // 1000
        stop = seg["end_ms"] * SR // 1000
        n_out = len(segments)
        for i in range(start, stop):
            t = (i - start) / SR
            dur = (stop - start) / SR
            env = min(1.0, t / 0.08) * (min(1.0, (dur - t) / 0.08) if dur > 0.16 else 1.0)
            kind = seg["kind"]
            if kind == "tone":
                v = 5000 * math.sin(2 * math.pi * 240 * t) + 3000 * math.sin(2 * math.pi * 480 * t)
            elif kind == "noise":
                noise_state += 1
                v = 3000 * idx2val(i, 777 + (noise_state % (n_out + 1))) + 1200 * env
            elif kind == "lowsnr":
                f0 = 140 if "1" in (seg.get("speaker") or "") else 175
                voice = sum(math.sin(2 * math.pi * f0 * h * t) for h in (1, 3, 5)) / 2.0
                v = 2000 * env * voice + 9000 * idx2val(i, 424 * f0)
            elif kind in ("speech", "overlap"):
                speakers = seg["speaker"].split("+") if seg["speaker"] else [""]
                v = 0.0
                for sp in speakers:
                    # stable per-speaker voiced pitch from a deterministic label hash
                    f0 = 90 + (sum(ord(c) for c in sp) % 12) * 22
                    voice = sum(math.sin(2 * math.pi * f0 * h * t) for h in (1, 3, 5, 7)) / 2.3
                    v += 9000 * env * voice / len(speakers)
            else:
                raise ValueError(f"unknown segment kind: {kind}")
            buf[i] = max(-32000.0, min(32000.0, buf[i] + v))
    return buf, overlap_ms


def recurrence(segments: list[dict]) -> list[str]:
    order: list[str] = []
    for seg in segments:
        sp = seg.get("speaker") or ""
        for s in _splits(sp):
            if s not in order:
                order.append(s)
    return order


def _splits(sp: str) -> list[str]:
    return [s for s in sp.split("+") if s]


SPECS: list[dict] = [
    {
        "name": "dev-ru-two-recurrence.wav",
        "language": "ru",
        "purpose": "development",
        "notes": "2 speakers; 2.5 s leading silence; speaker_1 returns last (recurrence); short one-word-length replies",
        "segments": [
            {"kind": "silence", "start_ms": 0, "end_ms": 2500, "speaker": None},
            {"kind": "speech", "start_ms": 2500, "end_ms": 5900, "speaker": "speaker_1"},
            {"kind": "speech", "start_ms": 5900, "end_ms": 7200, "speaker": "speaker_2"},
            {"kind": "speech", "start_ms": 7200, "end_ms": 8100, "speaker": "speaker_1"},
            {"kind": "speech", "start_ms": 8100, "end_ms": 8600, "speaker": "speaker_2"},
            {"kind": "speech", "start_ms": 8600, "end_ms": 10100, "speaker": "speaker_1"},
            {"kind": "speech", "start_ms": 10100, "end_ms": 10700, "speaker": "speaker_2"},
            {"kind": "speech", "start_ms": 10700, "end_ms": 12900, "speaker": "speaker_1"},
            {"kind": "silence", "start_ms": 12900, "end_ms": 14013, "speaker": None},
        ],
    },
    {
        "name": "dev-en-three-overlap.wav",
        "language": "en",
        "purpose": "development",
        "notes": "3 speakers; 12.0-13.5 s concurrent overlap of speaker_1+speaker_2 (each segment owned by ONE speaker); noise 16-20 s; speaker_2 recurrence",
        "segments": [
            {"kind": "silence", "start_ms": 0, "end_ms": 1200, "speaker": None},
            {"kind": "speech", "start_ms": 1200, "end_ms": 4700, "speaker": "speaker_1"},
            {"kind": "speech", "start_ms": 4700, "end_ms": 8000, "speaker": "speaker_2"},
            {"kind": "speech", "start_ms": 8000, "end_ms": 12000, "speaker": "speaker_3"},
            {"kind": "overlap", "start_ms": 12000, "end_ms": 13500, "speaker": "speaker_1+speaker_2"},
            {"kind": "speech", "start_ms": 13500, "end_ms": 16000, "speaker": "speaker_3"},
            {"kind": "noise", "start_ms": 16000, "end_ms": 20000, "speaker": None},
            {"kind": "speech", "start_ms": 20000, "end_ms": 23400, "speaker": "speaker_2"},
            {"kind": "silence", "start_ms": 23400, "end_ms": 26000, "speaker": None},
        ],
    },
    {
        "name": "dev-one-word.wav",
        "language": "en",
        "purpose": "development",
        "notes": "alternating one-word-length replies after 2 s silence",
        "segments": [
            {"kind": "silence", "start_ms": 0, "end_ms": 2000, "speaker": None},
            {"kind": "speech", "start_ms": 2000, "end_ms": 2700, "speaker": "speaker_1"},
            {"kind": "speech", "start_ms": 2700, "end_ms": 3300, "speaker": "speaker_2"},
            {"kind": "speech", "start_ms": 3300, "end_ms": 4000, "speaker": "speaker_1"},
            {"kind": "silence", "start_ms": 4000, "end_ms": 6000, "speaker": None},
        ],
    },
    {
        "name": "dev-uncertain-speech.wav",
        "language": "en",
        "purpose": "development",
        "notes": "two low-SNR speech-like segments (uncertain speech); no confident reference speakers",
        "segments": [
            {"kind": "silence", "start_ms": 0, "end_ms": 500, "speaker": None},
            {"kind": "lowsnr", "start_ms": 500, "end_ms": 3800, "speaker": "speaker_1"},
            {"kind": "lowsnr", "start_ms": 3800, "end_ms": 6600, "speaker": "speaker_2"},
            {"kind": "silence", "start_ms": 6600, "end_ms": 8000, "speaker": None},
        ],
    },
    # --- final check set (disjoint: different durations and speaker labels) ---
    {
        "name": "final-ru-two-short.wav",
        "language": "ru",
        "purpose": "final-check",
        "notes": "two speakers, 1.8 s leading silence, short replies, recurrence",
        "segments": [
            {"kind": "silence", "start_ms": 0, "end_ms": 1800, "speaker": None},
            {"kind": "speech", "start_ms": 1800, "end_ms": 5300, "speaker": "speaker_A"},
            {"kind": "speech", "start_ms": 5300, "end_ms": 7200, "speaker": "speaker_B"},
            {"kind": "speech", "start_ms": 7200, "end_ms": 9000, "speaker": "speaker_A"},
            {"kind": "speech", "start_ms": 9000, "end_ms": 11000, "speaker": "speaker_B"},
            {"kind": "silence", "start_ms": 11000, "end_ms": 12500, "speaker": None},
        ],
    },
    {
        "name": "final-en-three-speakers.wav",
        "language": "en",
        "purpose": "final-check",
        "notes": "three speakers; 2.5 s leading silence; one-word-length closing turns",
        "segments": [
            {"kind": "silence", "start_ms": 0, "end_ms": 2500, "speaker": None},
            {"kind": "speech", "start_ms": 2500, "end_ms": 6500, "speaker": "p1"},
            {"kind": "speech", "start_ms": 6500, "end_ms": 10500, "speaker": "p2"},
            {"kind": "speech", "start_ms": 10500, "end_ms": 14000, "speaker": "p3"},
            {"kind": "speech", "start_ms": 14000, "end_ms": 17000, "speaker": "p1"},
            {"kind": "speech", "start_ms": 17000, "end_ms": 17700, "speaker": "p2"},
            {"kind": "speech", "start_ms": 17700, "end_ms": 18300, "speaker": "p3"},
            {"kind": "silence", "start_ms": 18300, "end_ms": 21300, "speaker": None},
        ],
    },
    {
        "name": "final-unsupported-tone.wav",
        "language": "unsupported",
        "purpose": "final-check",
        "notes": "flat mid-band tones only; no speech-like structure anywhere",
        "segments": [
            {"kind": "tone", "start_ms": 0, "end_ms": 6000, "speaker": None},
            {"kind": "silence", "start_ms": 6000, "end_ms": 7000, "speaker": None},
            {"kind": "tone", "start_ms": 7000, "end_ms": 11000, "speaker": None},
        ],
    },
    {
        "name": "final-uncertain-low-snr.wav",
        "language": "ru",
        "purpose": "final-check",
        "notes": "single very low-SNR voiced segment (uncertain speech case)",
        "segments": [
            {"kind": "silence", "start_ms": 0, "end_ms": 900, "speaker": None},
            {"kind": "lowsnr", "start_ms": 900, "end_ms": 4500, "speaker": "speaker_x"},
            {"kind": "silence", "start_ms": 4500, "end_ms": 7000, "speaker": None},
        ],
    },
]


def main() -> None:
    manifest = {
        "license": "CC0-1.0 (team-generated synthetic audio; no third-party work)",
        "generated_by": "tests/fixtures/generate_fixtures.py (deterministic, stdlib only)",
        "consent": "not applicable (no personal data, no human speakers recorded)",
        "caveat": "SYNTHETIC STAND-INS, NOT REAL SPEECH. Valid for offset/speaker/"
        "recurrence/durability checks and metric plumbing. Any WER computed on"
        " these files is meaningless and must not be reported.",
        "references": [],
    }
    for spec in SPECS:
        buf, overlap_ms = synth(spec["segments"])
        p = HERE / str(spec["name"])
        wav_write(p, buf)
        leading_ms = (
            spec["segments"][0]["end_ms"] if spec["segments"][0]["kind"] == "silence" else 0
        )
        manifest["references"].append(
            {
                "file": p.name,
                "expect_duration_ms": len(buf) * 1000 // SR,
                "language": str(spec["language"]),
                "split_purpose": str(spec["purpose"]),
                "checksum_sha256": hashlib.sha256(p.read_bytes()).hexdigest(),
                "consent_or_license": manifest["license"],
                "notes": str(spec.get("notes", "")),
                "speaker_recurrence": recurrence(spec["segments"]),
                "leading_silence_ms": leading_ms,
                "overlap_ms": overlap_ms,
                "segments": spec["segments"],
            }
        )
    (HERE / "manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    (HERE / "SHA256SUMS.txt").write_text(
        "".join(
            "{}  {}\n".format(ref["checksum_sha256"], ref["file"])
            for ref in manifest["references"]
        ),
        encoding="utf-8",
    )
    print("wrote %d fixture files + manifest.json + SHA256SUMS.txt" % len(SPECS))


if __name__ == "__main__":
    main()
