from pathlib import Path
import sys

source = Path(sys.argv[1]) / "sherpa-onnx/csrc"


def replace_once(text: str, old: str, new: str) -> str:
    if text.count(old) != 1:
        raise ValueError(f"Unexpected sherpa source around {old!r}")
    return text.replace(old, new)


session = source / "session.cc"
text = session.read_text()
if 'new_provider_str == "nnapi-gigaam"' not in text:
    text = replace_once(
        text,
        "  Provider p = StringToProvider(new_provider_str);",
        '  const bool gigaam_nnapi = new_provider_str == "nnapi-gigaam";\n'
        '  Provider p = StringToProvider(gigaam_nnapi ? "nnapi" : new_provider_str);',
    )
    text = replace_once(
        text,
        "      // nnapi_flags |= NNAPI_FLAG_USE_FP16;",
        "      if (gigaam_nnapi) nnapi_flags |= NNAPI_FLAG_USE_FP16;",
    )
    session.write_text(text)

model = source / "offline-transducer-nemo-model.cc"
text = model.read_text()
if "UseCpuDecoderForGigaAm" not in text:
    for init in ("    InitEncoder(nullptr, 0);", "      InitEncoder(buf.data(), buf.size());\n    }"):
        text = replace_once(text, init, init + "\n    UseCpuDecoderForGigaAm();")
    text = replace_once(
        text,
        " private:\n  void InitEncoder(",
        """ private:
  void UseCpuDecoderForGigaAm() {
    const auto end = config_.provider.find(':');
    if (config_.provider.substr(0, end) != "nnapi-gigaam") return;
    // The Pixel Edge TPU rejects the recurrent decoder. Keep decoder and
    // per-frame joiner on CPU while the encoder uses NNAPI where supported.
    auto cpu_config = config_;
    cpu_config.provider.replace(0, end, "cpu");
    sess_opts_ = GetSessionOptions(cpu_config);
  }

  void InitEncoder(""",
    )
    model.write_text(text)
