#!/usr/bin/env bash
# Build the pinned sherpa runtime with NNAPI and the matching ORT Java bindings.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
work="${NEURO_NNAPI_CACHE:-$repo/.gradle/nnapi}"
ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME}/ndk/28.2.13676358}"
source_dir="$work/source"
ort_dir="$work/ort"
version=2.0.1-nnapi3
mkdir -p "$work"
exec 9>"$work/prepare.lock"
flock 9

build_key="$(
  sha256sum "$repo/scripts/"{prepare-nnapi.sh,prepare-deps.sh,patch-sherpa-jni.py,patch-sherpa-gigaam.py,nnapi-probe.cpp} "$ndk/source.properties" \
    | cut -d' ' -f1 | sha256sum | cut -d' ' -f1
)"
destination="$repo/libs/repo/com/k2fsa/sherpa/onnx/$version"
if python3 - "$destination" "$version" "$build_key" <<'PY_CACHE'
import hashlib, json, sys
from pathlib import Path
directory, version, key = sys.argv[1:]
path = Path(directory)
try:
    manifest = json.loads((path / 'build-manifest.json').read_text())
    assert manifest['build_key'] == key and (path / f'onnx-{version}.pom').is_file()
    assert hashlib.sha256((path / f'onnx-{version}.aar').read_bytes()).hexdigest() == manifest['aar_sha256']
except (OSError, ValueError, KeyError, AssertionError):
    sys.exit(1)
PY_CACHE
then
  echo "NNAPI artifact already verified: $destination"
  exit 0
fi

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

for abi in arm64-v8a x86_64; do
  build_dir="$work/$abi"
  target=aarch64-linux-android
  [[ "$abi" != x86_64 ]] || target=x86_64-linux-android
  SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ort_dir/headers" \
  SHERPA_ONNXRUNTIME_LIB_DIR="$ort_dir/jni/$abi" \
  cmake -S "$source_dir" -B "$build_dir" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-27 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
    -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 \
    -DSHERPA_ONNX_ENABLE_JNI=ON -DSHERPA_ONNX_ENABLE_PYTHON=OFF \
    -DSHERPA_ONNX_ENABLE_BINARY=OFF -DSHERPA_ONNX_ENABLE_C_API=OFF \
    -DSHERPA_ONNX_ENABLE_TTS=OFF -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
    -DSHERPA_ONNX_ENABLE_TESTS=OFF -DSHERPA_ONNX_ENABLE_CHECK=OFF \
    -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=ON
  cmake --build "$build_dir" --target sherpa-onnx-jni --parallel "${NEURO_NNAPI_JOBS:-4}"

  bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
  "$bin/${target}27-clang++" -shared -fPIC -O2 -static-libstdc++ \
    -fvisibility=hidden -ffunction-sections -fdata-sections \
    -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 \
    "$repo/scripts/nnapi-probe.cpp" -ldl -o "$build_dir/lib/libneuro_nnapi_probe.so"
  "$bin/llvm-strip" --strip-debug "$build_dir/lib/libsherpa-onnx-jni.so"
  "$bin/llvm-strip" --strip-debug "$build_dir/lib/libneuro_nnapi_probe.so"
done
"$repo/scripts/prepare-deps.sh"

python3 - "$repo" "$work" "$ort_dir" "$version" "$build_key" <<'PY'
from pathlib import Path
import hashlib, json, sys, zipfile
repo, work, ort = map(Path, sys.argv[1:4])
version, build_key = sys.argv[4:]
base = repo / 'libs/repo/com/k2fsa/sherpa/onnx'
destination = base / version
destination.mkdir(parents=True, exist_ok=True)
original = base / '2.0.1/onnx-2.0.1.aar'
aar = destination / f'onnx-{version}.aar'
abis = ('arm64-v8a', 'x86_64')
replacements = {'libs/onnxruntime.jar': ort / 'classes.jar'}
for abi in abis:
    replacements.update({
        f'jni/{abi}/libsherpa-onnx-jni.so': work / abi / 'lib/libsherpa-onnx-jni.so',
        f'jni/{abi}/libonnxruntime.so': ort / 'jni' / abi / 'libonnxruntime.so',
        f'jni/{abi}/libonnxruntime4j_jni.so': ort / 'jni' / abi / 'libonnxruntime4j_jni.so',
        f'jni/{abi}/libneuro_nnapi_probe.so': work / abi / 'lib/libneuro_nnapi_probe.so',
    })

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
manifest = {'sherpa': '2.0.1', 'onnxruntime': '1.28.2', 'android_api': 27, 'nnapi_abis': list(abis), 'nnapi_cpu_disabled': True,
            'build_key': build_key,
            'aar_sha256': hashlib.sha256(aar.read_bytes()).hexdigest(),
            'probe_sha256': hashlib.sha256((repo/'scripts/nnapi-probe.cpp').read_bytes()).hexdigest(),
            'jni_patch_sha256': hashlib.sha256((repo/'scripts/patch-sherpa-jni.py').read_bytes()).hexdigest(),
            'gigaam_patch_sha256': hashlib.sha256((repo/'scripts/patch-sherpa-gigaam.py').read_bytes()).hexdigest()}
(destination/'build-manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
print(f'NNAPI artifact ready: {aar}')
PY
