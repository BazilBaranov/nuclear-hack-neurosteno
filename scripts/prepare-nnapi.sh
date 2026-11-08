#!/usr/bin/env bash
# Build the pinned sherpa runtime with NNAPI enabled for arm64, retaining CPU x86_64.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
work="${NEURO_NNAPI_CACHE:-$repo/.gradle/nnapi}"
ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME}/ndk/28.2.13676358}"
source_dir="$work/source"
ort_dir="$work/ort"
build_dir="$work/arm64"
mkdir -p "$work"

fetch() {
  local url="$1" destination="$2" digest="$3"
  if [[ ! -f "$destination" ]]; then
    curl -fL --retry 3 "$url" -o "$destination.part"
    printf '%s  %s\n' "$digest" "$destination.part" | sha256sum -c -
    mv "$destination.part" "$destination"
  fi
  printf '%s  %s\n' "$digest" "$destination" | sha256sum -c -
}

fetch 'https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/refs/tags/v2.0.1' \
  "$work/source.tar.gz" '24ecc0fccf6344c2556e7c229c849e9f61bf9456b915615197d85f35e3f177e7'
fetch 'https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip' \
  "$work/ort.zip" '01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d'
if [[ ! -f "$source_dir/CMakeLists.txt" ]]; then
  mkdir -p "$source_dir"
  tar -xzf "$work/source.tar.gz" --strip-components=1 -C "$source_dir"
fi
if [[ ! -f "$ort_dir/headers/onnxruntime_cxx_api.h" ]]; then
  python3 -m zipfile -e "$work/ort.zip" "$ort_dir"
fi

# Disable the NNAPI reference CPU; unsupported operations can still use ORT CPU.
# Unique profiling names prevent encoder/decoder sessions overwriting one another.
python3 - "$source_dir/sherpa-onnx/csrc/session.cc" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
if 'uint32_t nnapi_flags = NNAPI_FLAG_CPU_DISABLED;' not in s:
    assert s.count('uint32_t nnapi_flags = 0;') == 1
    s = s.replace('uint32_t nnapi_flags = 0;', 'uint32_t nnapi_flags = NNAPI_FLAG_CPU_DISABLED;')
if 'static std::atomic<uint64_t> profile_id{0};' not in s:
    old = 'sess_opts.EnableProfiling(\n        SHERPA_ONNX_TO_ORT_PATH(config["ProfilingFilePrefix"]));'
    assert s.count(old) == 1
    s = s.replace('#include <algorithm>', '#include <algorithm>\n#include <atomic>')
    s = s.replace(old, 'static std::atomic<uint64_t> profile_id{0};\n    std::string unique_prefix = config["ProfilingFilePrefix"] + "_" + std::to_string(profile_id.fetch_add(1));\n    sess_opts.EnableProfiling(SHERPA_ONNX_TO_ORT_PATH(unique_prefix));')
if s != p.read_text():
    p.write_text(s)
PY
python3 "$repo/scripts/patch-sherpa-jni.py" "$source_dir"
python3 "$repo/scripts/patch-sherpa-gigaam.py" "$source_dir"

SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ort_dir/headers" \
SHERPA_ONNXRUNTIME_LIB_DIR="$ort_dir/jni/arm64-v8a" \
cmake -S "$source_dir" -B "$build_dir" \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-27 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
  -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 \
  -DSHERPA_ONNX_ENABLE_JNI=ON -DSHERPA_ONNX_ENABLE_PYTHON=OFF \
  -DSHERPA_ONNX_ENABLE_BINARY=OFF -DSHERPA_ONNX_ENABLE_C_API=OFF \
  -DSHERPA_ONNX_ENABLE_TTS=OFF -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
  -DSHERPA_ONNX_ENABLE_TESTS=OFF -DSHERPA_ONNX_ENABLE_CHECK=OFF \
  -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=ON
cmake --build "$build_dir" --target sherpa-onnx-jni -j2

bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
"$bin/aarch64-linux-android27-clang++" -shared -fPIC -O2 -static-libstdc++ \
  -fvisibility=hidden -ffunction-sections -fdata-sections \
  -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 \
  "$repo/scripts/nnapi-probe.cpp" -ldl -o "$build_dir/lib/libneuro_nnapi_probe.so"
"$bin/llvm-strip" --strip-debug "$build_dir/lib/libsherpa-onnx-jni.so"
"$bin/llvm-strip" --strip-debug "$build_dir/lib/libneuro_nnapi_probe.so"
"$repo/scripts/prepare-deps.sh"

python3 - "$repo" "$build_dir" "$ort_dir" <<'PY'
from pathlib import Path
import hashlib, json, sys, zipfile
repo, build, ort = map(Path, sys.argv[1:])
version = '2.0.1-nnapi2'
base = repo / 'libs/repo/com/k2fsa/sherpa/onnx'
destination = base / version
destination.mkdir(parents=True, exist_ok=True)
original = base / '2.0.1/onnx-2.0.1.aar'
aar = destination / f'onnx-{version}.aar'
replacements = {
    'jni/arm64-v8a/libsherpa-onnx-jni.so': build / 'lib/libsherpa-onnx-jni.so',
    'jni/arm64-v8a/libonnxruntime.so': ort / 'jni/arm64-v8a/libonnxruntime.so',
    'jni/arm64-v8a/libneuro_nnapi_probe.so': build / 'lib/libneuro_nnapi_probe.so',
}
temporary = aar.with_suffix('.aar.tmp')
with zipfile.ZipFile(original) as source, zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED) as target:
    for entry in source.infolist():
        if entry.filename not in replacements:
            target.writestr(entry, source.read(entry))
    for name, path in replacements.items():
        entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        target.writestr(entry, path.read_bytes())
with zipfile.ZipFile(temporary) as rebuilt, zipfile.ZipFile(original) as source:
    assert rebuilt.read('classes.jar') == source.read('classes.jar')
    assert all(rebuilt.getinfo(name).file_size > 0 for name in replacements)
    assert 'jni/x86_64/libsherpa-onnx-jni.so' in rebuilt.namelist()
temporary.replace(aar)
pom = destination / f'onnx-{version}.pom'
pom.write_text(f'<project><modelVersion>4.0.0</modelVersion><groupId>com.k2fsa.sherpa</groupId><artifactId>onnx</artifactId><version>{version}</version><packaging>aar</packaging></project>\n')
for path in (aar, pom):
    path.with_suffix(path.suffix + '.sha1').write_text(hashlib.sha1(path.read_bytes()).hexdigest())
manifest = {'sherpa': '2.0.1', 'onnxruntime': '1.28.2', 'arm64_api': 27, 'nnapi_cpu_disabled': True,
            'aar_sha256': hashlib.sha256(aar.read_bytes()).hexdigest(),
            'probe_sha256': hashlib.sha256((repo/'scripts/nnapi-probe.cpp').read_bytes()).hexdigest(),
            'jni_patch_sha256': hashlib.sha256((repo/'scripts/patch-sherpa-jni.py').read_bytes()).hexdigest(),
            'gigaam_patch_sha256': hashlib.sha256((repo/'scripts/patch-sherpa-gigaam.py').read_bytes()).hexdigest()}
(destination/'build-manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
print(f'NNAPI artifact ready: {aar}')
PY
