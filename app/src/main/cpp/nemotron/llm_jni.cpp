#include <jni.h>
#include "local_llm.h"
#include <exception>
#include <new>
#include <stdexcept>

namespace {
void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_neurostenografist_inference_LocalPunctuation_nativeCreate(JNIEnv* env, jobject, jstring path, jint threads) {
    if (!path) { fail(env, "Не указан путь к LLM"); return 0; }
    const char* raw = env->GetStringUTFChars(path, nullptr);
    if (!raw) return 0;
    std::string filename(raw);
    env->ReleaseStringUTFChars(path, raw);
    try { return reinterpret_cast<jlong>(new neuro::LocalLlm(filename, threads)); }
    catch (const std::exception& e) { fail(env, e.what()); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_neurostenografist_inference_LocalPunctuation_nativeGenerate(JNIEnv* env, jobject owner, jlong handle, jbyteArray prompt, jint max_tokens) {
    if (!handle || !prompt) { fail(env, "LLM закрыта или текст отсутствует"); return nullptr; }
    const auto length = env->GetArrayLength(prompt);
    if (length > 8192) { fail(env, "Слишком длинный запрос к LLM"); return nullptr; }
    std::string input(length, '\0');
    env->GetByteArrayRegion(prompt, 0, length, reinterpret_cast<jbyte*>(input.data()));
    if (env->ExceptionCheck()) return nullptr;
    auto klass = env->GetObjectClass(owner);
    auto cancellation = env->GetMethodID(klass, "isCancellationRequested", "()Z");
    env->DeleteLocalRef(klass);
    if (!cancellation) return nullptr;
    try {
        const auto output = reinterpret_cast<neuro::LocalLlm*>(handle)->generate(input, max_tokens, [&] {
            return env->CallBooleanMethod(owner, cancellation) || env->ExceptionCheck();
        });
        if (env->ExceptionCheck()) return nullptr;
        auto result = env->NewByteArray(output.size());
        if (result) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte*>(output.data()));
        return result;
    } catch (const std::exception& e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_neurostenografist_inference_LocalPunctuation_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<neuro::LocalLlm*>(handle);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_neurostenografist_inference_NativeAcceleration_nativeDevices(JNIEnv* env, jobject) {
    try {
        const auto devices = neuro::devices();
        auto result = env->NewObjectArray(devices.size(), env->FindClass("java/lang/String"), nullptr);
        if (!result) return nullptr;
        for (size_t i = 0; i < devices.size(); ++i) {
            auto text = env->NewStringUTF(devices[i].c_str());
            if (!text) return nullptr;
            env->SetObjectArrayElement(result, i, text);
            env->DeleteLocalRef(text);
        }
        return result;
    } catch (const std::exception& e) { fail(env, e.what()); return nullptr; }
}
