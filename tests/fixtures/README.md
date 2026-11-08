# Test fixture corpus (`tests/fixtures/`)

Mixed audio fixture set split into a **development set** (`dev-*.wav`) and a
**disjoint final-check set** (`final-*.wav`). Final-check files use different
durations, seeds and speaker labels; they are never used to tune the pipeline.

## What these files are

- Own, team-generated **synthetic stand-ins** (mono 16 kHz PCM16 WAV), produced
  deterministically by `generate_fixtures.py` (standard library only, integer
  synthesis; re-running reproduces byte-identical files — see checksums below).
- Coverage: RU/EN-labelled conversations (2 speakers and 3+ speakers, recurring
  speakers, short/one-word-length replies, leading silence, a noise-band case),
  an unsupported-language tones-only case, and uncertain low-SNR speech cases.

## What these files are NOT

- **Not real speech.** The waveforms are coded voiced hums / noise / tones on
  known timelines. They support decode, import, timeline-persistence, offset and
  speaker-recurrence regression checks and the metric plumbing self-checks.
  Any WER (or claimed diarization accuracy) computed on them is meaningless and
  must not be reported — see `manifest.json` caveat and docs/TESTING.md.
- No third-party audio was bundled and no real person consent form applies
  (license: CC0-1.0 for everything in this directory).

## Using a fixture on-device

`scripts/build.sh` etc. push fixtures via `adb push`; the acceptance harnesses
under `app/src/androidTest/.../acceptance/` copy an APK-testdata WAV to app
storage and run the real pipeline. Prepared ground truth lives exclusively in
`manifest.json` (segments, offsets, recurrence); it is used only to verify
timeline persistence and DER metric self-checks, never substituted into
inference.
