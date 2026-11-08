#!/usr/bin/env bash
# Delivery verification: records the exact delivered source snapshot (content
# fingerprint, dirty state) and the built APK, and proves the APK ships the
# native runtimes. Hard-fails on any mismatch; never fabricates measurements.
#
# Provenance honesty: the fingerprint NAMES the delivered source bytes but does
# NOT prove the supplied APK was built from them — the apk-vs-source match
# requires a documented rebuild of this snapshot (scripts/build.sh). The final
# rebuild + metadata generation happen after all delivery lanes integrate.
# Usage: scripts/verify-delivery.sh <apk> [expected ABI ...]
set -euo pipefail

cd "$(dirname "$0")/.."
APK="${1:?usage: verify-delivery.sh <apk> [expected ABI ...]}"
shift
if [ "$#" -eq 0 ]; then set -- arm64-v8a x86_64; fi
OUT=artifacts
mkdir -p "$OUT"

fail() { echo "VERIFY-FAIL: $*" >&2; exit 1; }

[ -f "$APK" ] || fail "APK not found: $APK"

REV="$(git rev-parse HEAD)"
DIRTY="$(git status --porcelain)"
DIRTY_ENTRIES="$(printf '%s\n' "$DIRTY" | sed '/^$/d' | wc -l | tr -d ' ')"
# Content identity of the delivered snapshot: working-tree bytes of all
# relevant source/build/config files (staged+unstaged+relevant untracked),
# caches/build outputs/artifacts/.git excluded — see scripts/source-fingerprint.sh.
SRC_FP="$(scripts/source-fingerprint.sh | sed -n 's/^source_content_sha256=//p')"
APK_SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
APK_SIZE="$(stat -c '%s' "$APK")"

echo "=== delivery verification ==="
echo "apk=$APK"
echo "apk_sha256=$APK_SHA"
echo "apk_bytes=$APK_SIZE"
echo "source_revision=$REV (reference only; NOT the source identity)"
echo "source_content_sha256=$SRC_FP (working-tree bytes incl. staged/unstaged/relevant untracked)"
echo "source_dirty=$( [ -n "$DIRTY" ] && echo true || echo false ) (entries=$DIRTY_ENTRIES)"
echo "generated_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# 1. Working tree state matters more than HEAD: the fingerprint covers the
#    exact bytes, but bare HEAD never identifies a dirty snapshot's source.
if [ -n "$DIRTY" ]; then
  echo "warn: working tree is dirty; the fingerprint is valid only for these exact bytes"
  printf '%s\n' "$DIRTY" | sed 's/^/dirty: /'
  echo "note: APK provenance is only provable by rebuilding THIS snapshot (scripts/build.sh) and re-running this script; fingerprint alone does not claim apk-vs-source identity"
fi
[ -n "$SRC_FP" ] || fail "source fingerprint could not be computed (scripts/source-fingerprint.sh)"

# 2. Native ABI coverage inside the APK (jacoco of jni lib manifests).
ABIS="$(unzip -Z1 "$APK" 'lib/*/*.so' 2>/dev/null | sed -n 's|^lib/\([^/]*\)/.*|\1|p' | sort -u)"
echo "abi_list:"
[ -n "$ABIS" ] || fail "APK ships no native libs under lib/*"
for abi in $ABIS; do echo "  - $abi"; done
echo "abi_list:" >/dev/null
ABIS_LIST="$(echo "$ABIS" | tr '\n' ',' | sed 's/,$//')"
EXPECTED_ABIS="$(printf '%s\n' "$@" | sort -u)"
[ "$ABIS" = "$EXPECTED_ABIS" ] || fail "unexpected APK ABI list: $ABIS_LIST"
for expected in "$@"; do
  unzip -Z1 "$APK" "lib/$expected/libneuro_nemotron.so" >/dev/null 2>&1 || fail "Nemotron runtime missing for $expected"
  unzip -Z1 "$APK" "lib/$expected/libsherpa-onnx-jni.so" >/dev/null 2>&1 || fail "ONNX JNI missing for $expected"
  unzip -Z1 "$APK" "lib/$expected/libonnxruntime.so" >/dev/null 2>&1 || fail "ONNX runtime missing for $expected"
  unzip -Z1 "$APK" "lib/$expected/libonnxruntime4j_jni.so" >/dev/null 2>&1 || fail "ONNX Java JNI missing for $expected"
  unzip -Z1 "$APK" "lib/$expected/libneuro_nnapi_probe.so" >/dev/null 2>&1 || fail "NNAPI driver probe missing for $expected"
  echo "abi_$expected=present"
done
while read -r digest name url; do
  actual="$(unzip -p "$APK" "assets/models/$name" | sha256sum | cut -d' ' -f1)" ||
    fail "missing bundled model asset $name"
  [[ "$actual" == "$digest" ]] || fail "bundled model asset checksum mismatch: $name"
done < scripts/bundled-models.txt
echo "model_weights_in_apk=whisper-small,pyannote-titanet"

# 3. Rebuilt dependency: compare actual bytes to its generated build manifest.
DEPS_DIR=libs/repo/com/k2fsa/sherpa/onnx/2.0.1-nnapi3
DEPS_SHA="$(python3 - "$DEPS_DIR" <<'PY'
import hashlib, json, pathlib, sys
p = pathlib.Path(sys.argv[1])
m = json.loads((p / 'build-manifest.json').read_text())
digest = hashlib.sha256((p / 'onnx-2.0.1-nnapi3.aar').read_bytes()).hexdigest()
assert digest == m['aar_sha256'], 'Rebuilt AAR checksum mismatch'
assert m['nnapi_cpu_disabled'] and m['android_api'] >= 27 and set(m['nnapi_abis']) == {'arm64-v8a', 'x86_64'}
patch_digest = hashlib.sha256(pathlib.Path('scripts/patch-sherpa-gigaam.py').read_bytes()).hexdigest()
assert patch_digest == m['gigaam_patch_sha256'], 'Rebuild the AAR after changing the GigaAM patch'
print(digest)
PY
)" || fail "NNAPI AAR is missing or differs from its build manifest"
echo "sherpa_onnx_aar_sha256=$DEPS_SHA"

# 4. Fixture corpus integrity (delivered evaluation corpus must be intact).
( cd tests/fixtures && sha256sum -c SHA256SUMS.txt >/dev/null ) \
  && echo "fixtures_checksums=ok" || fail "fixture corpus checksum mismatch"

# 5. Persist a machine-readable summary (the manifest of this delivery run).
SUMMARY="$OUT/delivery-summary.env"
cat > "$SUMMARY" <<EOF
SOURCE_REVISION=$REV
SOURCE_CONTENT_SHA256=$SRC_FP
SOURCE_DIRTY=$( [ -n "$DIRTY" ] && echo true || echo false )
SOURCE_DIRTY_ENTRIES=$DIRTY_ENTRIES
APK_PATH=$(readlink -f "$APK")
APK_SHA256=$APK_SHA
APK_BYTES=$APK_SIZE
PROVENANCE_NOTE=fingerprint identifies the delivered source bytes; APK provenance requires a documented rebuild from this snapshot (final rebuild+metadata regeneration happen after lane integration)
ABIS=$ABIS_LIST
SHERPA_ONNX_AAR_SHA256=$DEPS_SHA
GENERATED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
echo "summary=$SUMMARY"

echo "verify-delivery: OK (structural checks passed; runtime device evidence is recorded separately in artifacts/)"
