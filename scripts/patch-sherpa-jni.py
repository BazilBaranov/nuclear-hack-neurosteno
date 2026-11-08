"""Make the pinned file-based LID/diarization JNI paths exception-safe.

ASR already uses SafeJNI and RethrowCxxExceptionAsJava in sherpa 2.0.1.
These missing guards otherwise abort Android when an NNAPI driver rejects a model.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1]) / "sherpa-onnx/jni"


def replace(path, before, after):
    text = path.read_text()
    if after in text:
        return
    assert text.count(before) == 1, (path, before)
    path.write_text(text.replace(before, after))


for filename, classname, variable in [
    ("spoken-language-identification.cc", "SpokenLanguageIdentification", "tagger"),
    ("offline-speaker-diarization.cc", "OfflineSpeakerDiarization", "sd"),
]:
    before = f"auto {variable} = new sherpa_onnx::{classname}(config);"
    after = f'''sherpa_onnx::{classname} *{variable} = nullptr;
  try {{
    {variable} = new sherpa_onnx::{classname}(config);
  }} catch (const std::exception &e) {{
    RethrowCxxExceptionAsJava(env, e, "Failed to create {classname}");
    return 0;
  }}'''
    replace(root / filename, before, after)

replace(root / "offline-speaker-diarization.cc", '''  jfloat *p = env->GetFloatArrayElements(samples, nullptr);
  jsize n = env->GetArrayLength(samples);
  auto segments = sd->Process(p, n).SortByStartTime();
  env->ReleaseFloatArrayElements(samples, p, JNI_ABORT);

  return ProcessImpl(env, segments);''', '''  // Copy before inference: a throwing driver must not leave a Java array pinned.
  jsize n = env->GetArrayLength(samples);
  std::vector<float> buffer(n);
  env->GetFloatArrayRegion(samples, 0, n, buffer.data());
  if (env->ExceptionCheck()) return nullptr;
  try {
    auto segments = sd->Process(buffer.data(), n).SortByStartTime();
    return ProcessImpl(env, segments);
  } catch (const std::exception &e) {
    RethrowCxxExceptionAsJava(env, e, "OfflineSpeakerDiarization_process");
    return nullptr;
  }''')

path = root / "spoken-language-identification.cc"
text = path.read_text()
start = text.index("Java_com_k2fsa_sherpa_onnx_SpokenLanguageIdentification_compute(")
body_start = text.index("{", start)
body_end = text.index("\n}", body_start)
body = text[body_start + 1:body_end]
if "RethrowCxxExceptionAsJava" not in body:
    guarded = '''
  try {''' + body + '''
  } catch (const std::exception &e) {
    RethrowCxxExceptionAsJava(env, e, "SpokenLanguageIdentification_compute");
    return nullptr;
  }'''
    path.write_text(text[:body_start + 1] + guarded + text[body_end:])

# LINK_FLAGS is a raw shell string; target_link_options preserves paths with spaces.
replace(root / "CMakeLists.txt", '''  set_target_properties(sherpa-onnx-jni PROPERTIES
    LINK_FLAGS "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/sherpa-onnx-symbols.lds"
  )''', '''  target_link_options(sherpa-onnx-jni PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/sherpa-onnx-symbols.lds"
  )''')
