#include <jni.h>
#include <algorithm>
#include <exception>
#include <memory>
#include <new>
#include <mutex>
#include <vector>
#include "diarizer.h"
#include "runtime.h"
#include "local_llm.h"

namespace {
std::mutex report_mutex;
std::string backend_report = "Nemotron ещё не запускался";
void report(const std::string& value) {
    std::lock_guard<std::mutex> lock(report_mutex);
    backend_report = value;
}
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_dev_neurostenografist_inference_NemotronDiarizer_nativeDiarize(
        JNIEnv* env, jobject, jstring path, jfloatArray audio, jboolean prefer_gpu, jint threads) {
    if (!path || !audio) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Missing model or audio");
        return nullptr;
    }
    const char* utf = env->GetStringUTFChars(path, nullptr);
    if (!utf) return nullptr;
    std::string model_path(utf);
    env->ReleaseStringUTFChars(path, utf);
    try {
        neuro::initialize_backends();
        const auto run = [&](bool use_gpu) -> jdoubleArray {
            ggml_runtime::Params params;
            params.use_gpu = use_gpu;
            params.n_threads = std::clamp<int>(threads, 1, 4);
            params.pe_bin_path = const_cast<char*>("");
            ggml_runtime::BackendManager backend(params);
            auto model = std::make_shared<nemo_speech::asr::DiarModel>(backend, model_path);
            nemo_speech::asr::Diarizer diarizer(model, {});
            auto stream = diarizer.streaming_diarize();
            const jsize size = env->GetArrayLength(audio);
            // Preserve one AOSC/FIFO state for the whole recording without pinning the
            // Java heap or copying the entire recording again into native memory.
            std::vector<float> chunk(16000 * 10);
            for (jsize offset = 0; offset < size;) {
                const jsize count = std::min<jsize>(chunk.size(), size - offset);
                env->GetFloatArrayRegion(audio, offset, count, chunk.data());
                if (env->ExceptionCheck()) return nullptr;
                stream->push(chunk.data(), count, 16000);
                offset += count;
            }
            stream->finish();
            const auto segments = stream->segments();
            std::vector<double> triples;
            triples.reserve(segments.size() * 3);
            const double duration = size / 16000.0;
            for (const auto& segment : segments) {
                const double start = std::clamp(segment.t0, 0.0, duration);
                const double end = std::clamp(segment.t1, start, duration);
                if (end > start) {
                    triples.push_back(segment.speaker);
                    triples.push_back(start);
                    triples.push_back(end);
                }
            }
            auto result = env->NewDoubleArray(triples.size());
            if (result && !triples.empty()) env->SetDoubleArrayRegion(result, 0, triples.size(), triples.data());
            if (result && !env->ExceptionCheck()) {
                const auto name = ggml_backend_name(backend.get_backends().front());
                report(use_gpu ? std::string("GPU ") + name + " + CPU" : "CPU");
            }
            return result;
        };
        if (!prefer_gpu) return run(false);
        try {
            return run(true);
        } catch (const std::exception& e) {
            auto result = run(false);
            if (result && !env->ExceptionCheck()) {
                report(std::string("CPU; Vulkan Nemotron недоступен: ") + e.what());
            }
            return result;
        }
    } catch (const std::bad_alloc&) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Nemotron: insufficient native memory");
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
    }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_neurostenografist_inference_NemotronDiarizer_nativeBackendReport(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(report_mutex);
    return env->NewStringUTF(backend_report.c_str());
}
