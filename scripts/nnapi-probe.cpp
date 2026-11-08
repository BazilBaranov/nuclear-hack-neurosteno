#include <jni.h>
#include <dlfcn.h>
#include <cstdint>
#include <string>
#include <vector>
#include <cstdio>

static std::vector<std::string> detectDevices() {
    std::vector<std::string> devices;
    void *library = dlopen("libneuralnetworks.so", RTLD_NOW | RTLD_LOCAL);
    if (library) {
        using Device = const void *;
        auto countDevices = reinterpret_cast<int (*)(uint32_t *)>(dlsym(library, "ANeuralNetworks_getDeviceCount"));
        auto getDevice = reinterpret_cast<int (*)(uint32_t, Device *)>(dlsym(library, "ANeuralNetworks_getDevice"));
        auto getName = reinterpret_cast<int (*)(Device, const char **)>(dlsym(library, "ANeuralNetworksDevice_getName"));
        auto getType = reinterpret_cast<int (*)(Device, int32_t *)>(dlsym(library, "ANeuralNetworksDevice_getType"));
        uint32_t count = 0;
        if (countDevices && getDevice && getName && getType && countDevices(&count) == 0) {
            for (uint32_t i = 0; i < count && i < 256; ++i) {
                Device device = nullptr;
                const char *name = nullptr;
                int32_t type = 0;
                if (getDevice(i, &device) == 0 && getName(device, &name) == 0 && name && getType(device, &type) == 0) {
                    devices.push_back(std::to_string(type) + "\t" + name);
                }
            }
        }
        dlclose(library);
    }
    return devices;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_neurostenografist_inference_OnnxAcceleration_nativeDevices(JNIEnv *env, jobject) {
    auto devices = detectDevices();
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(devices.size()), stringClass, nullptr);
    if (!result) return nullptr;
    for (jsize i = 0; i < static_cast<jsize>(devices.size()); ++i) {
        jstring value = env->NewStringUTF(devices[i].c_str());
        if (!value) return nullptr;
        env->SetObjectArrayElement(result, i, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

#ifdef NEURO_PROBE_MAIN
int main() {
    auto devices = detectDevices();
    for (const auto &device : devices) std::puts(device.c_str());
    std::printf("NNAPI devices: %zu\n", devices.size());
}
#endif
