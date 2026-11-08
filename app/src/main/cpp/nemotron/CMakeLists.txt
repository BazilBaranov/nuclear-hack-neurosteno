cmake_minimum_required(VERSION 3.22)
project(neuro_nemotron LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Keep native compilation bounded even when Ninja is invoked by Android Gradle.
set_property(GLOBAL PROPERTY JOB_POOLS compile_pool=1 link_pool=1)
set(CMAKE_JOB_POOL_COMPILE compile_pool)
set(CMAKE_JOB_POOL_LINK link_pool)
add_compile_options(-O2)
include(FetchContent)
# The ggml revision is the submodule pinned by the upstream NeMo-Speech.cpp commit.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
option(NEURO_VULKAN "Build the Vulkan GPU backend" ${ANDROID})
set(GGML_VULKAN ${NEURO_VULKAN} CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
if(NEURO_VULKAN)
    FetchContent_Declare(vulkan_headers
        URL https://codeload.github.com/KhronosGroup/Vulkan-Headers/tar.gz/2cd90f9d20df57eac214c148f3aed885372ddcfe
        URL_HASH SHA256=e3865307e665f6a4ac4f7bbca7b3ba95e4191c7f8adf9f04b084ef514a2454e9)
    FetchContent_Declare(spirv_headers
        URL https://codeload.github.com/KhronosGroup/SPIRV-Headers/tar.gz/2a611a970fdbc41ac2e3e328802aed9985352dca
        URL_HASH SHA256=c2225a49c3d7efa5c4f4ce4a6b42081e6ea3daca376f3353d9d7c2722d77a28a)
    FetchContent_MakeAvailable(vulkan_headers spirv_headers)
    set(Vulkan_INCLUDE_DIR "${vulkan_headers_SOURCE_DIR}/include" CACHE PATH "" FORCE)
    # The upstream ggml lookup expects an installed package; FetchContent already defines its target.
    file(WRITE "${spirv_headers_BINARY_DIR}/SPIRV-HeadersConfig.cmake"
        "if(NOT TARGET SPIRV-Headers::SPIRV-Headers)\nmessage(FATAL_ERROR \"SPIRV headers target missing\")\nendif()\n")
    set(SPIRV-Headers_DIR "${spirv_headers_BINARY_DIR}" CACHE PATH "" FORCE)
    if(DEFINED ENV{VULKAN_SDK})
        set(Vulkan_GLSLC_EXECUTABLE "$ENV{VULKAN_SDK}/bin/glslc" CACHE FILEPATH "" FORCE)
    elseif(ANDROID AND NOT Vulkan_GLSLC_EXECUTABLE)
        set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/linux-x86_64/glslc" CACHE FILEPATH "" FORCE)
    endif()
endif()
FetchContent_Declare(llama
    URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/bd4f514db14d87fded667787a7a963bfbaa98e89
    URL_HASH SHA256=b02af830447d56db65f6579414dee3c06d9d116cc1765d2539417ee8a7ff1dfa)
FetchContent_MakeAvailable(llama)
if(TARGET ggml-vulkan)
    # Backport ggml-org/llama.cpp#30106 for correct small matmuls on Mali.
    find_program(NEURO_PATCH_EXECUTABLE patch REQUIRED)
    set(VULKAN_PATCH "${CMAKE_CURRENT_SOURCE_DIR}/patches/vulkan-small-matmul.patch")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${VULKAN_PATCH}")
    execute_process(
        COMMAND "${NEURO_PATCH_EXECUTABLE}" -p1 --force --reverse --dry-run --input "${VULKAN_PATCH}"
        WORKING_DIRECTORY "${llama_SOURCE_DIR}"
        RESULT_VARIABLE patch_already_applied
        OUTPUT_QUIET ERROR_QUIET)
    if(NOT patch_already_applied EQUAL 0)
        execute_process(
            COMMAND "${NEURO_PATCH_EXECUTABLE}" -p1 --batch --forward --input "${VULKAN_PATCH}"
            WORKING_DIRECTORY "${llama_SOURCE_DIR}"
            COMMAND_ERROR_IS_FATAL ANY)
    endif()
    target_link_libraries(ggml-vulkan PRIVATE SPIRV-Headers::SPIRV-Headers)
endif()
set(V ${CMAKE_CURRENT_SOURCE_DIR}/vendor/src)
file(GLOB RUNTIME ${V}/runtime/ggml/*.cpp)
# Full llama provides the real logger and mmap implementation.
list(FILTER RUNTIME EXCLUDE REGEX "llama_log_shim\\.cpp$")
file(GLOB DIAR ${V}/asr/diar/*.cpp)
# The shared Sortformer graph also references the v2 FastConformer branch.
add_library(nemotron_core STATIC ${RUNTIME} ${DIAR}
    ${V}/asr/encoder/rope_transformer.cpp
    ${V}/asr/encoder/transformer_encoder.cpp
    ${V}/asr/encoder/fastconformer.cpp
    ${V}/asr/encoder/rel_pos_attention.cpp
    ${V}/asr/features/fe.cpp
    ${V}/common/audio_resampler.cpp
    ${V}/common/parameter_parser.cpp)
target_include_directories(nemotron_core PUBLIC
    ${V}/asr ${V}/asr/diar ${V}/asr/features ${V}/asr/encoder
    ${V}/runtime/ggml ${V}/common
    ${llama_SOURCE_DIR}/src ${llama_SOURCE_DIR}/include)
target_link_libraries(nemotron_core PUBLIC llama ggml ggml-base ggml-cpu)
add_library(neuro_llm STATIC local_llm.cpp)
target_link_libraries(neuro_llm PUBLIC llama)
option(NEURO_BUILD_HOST_TESTS "Build the real local LLM smoke test" OFF)
if(NEURO_BUILD_HOST_TESTS AND NOT ANDROID)
    add_executable(neuro_llm_smoke ../../../test/cpp/local_llm_smoke.cpp)
    target_include_directories(neuro_llm_smoke PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_link_libraries(neuro_llm_smoke PRIVATE neuro_llm)
endif()
if(ANDROID)
    add_library(neuro_nemotron SHARED jni.cpp llm_jni.cpp)
    target_link_libraries(neuro_nemotron PRIVATE nemotron_core neuro_llm log)
    target_link_options(neuro_nemotron PRIVATE "-Wl,-z,max-page-size=16384")
endif()
