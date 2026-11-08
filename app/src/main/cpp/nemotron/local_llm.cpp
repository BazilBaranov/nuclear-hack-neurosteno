#include "local_llm.h"
#include "llama.h"
#include "ggml-backend.h"
#include <algorithm>
#include <cstdlib>
#include <cstdio>
#include <mutex>
#include <stdexcept>
#include <string_view>

namespace neuro {
void initialize_backends() {
    static std::once_flag initialized;
    std::call_once(initialized, [] {
        // Nemotron's recurrent cache is incompatible with Vulkan graph optimization.
        setenv("GGML_VK_DISABLE_GRAPH_OPTIMIZE", "1", 0);
        // Avoid verbose per-node diagnostics on a phone during every decode.
        auto log = [](ggml_log_level level, const char* text, void*) {
            if (level != GGML_LOG_LEVEL_DEBUG) std::fputs(text, stderr);
        };
        llama_log_set(log, nullptr);
        ggml_log_set(log, nullptr);
        try {
            llama_backend_init();
            ggml_backend_load_all();
        } catch (const std::exception&) {
            // A throwing Vulkan inventory must not make the CPU path unusable.
            // The pinned ggml registry explicitly honors this switch.
            setenv("GGML_DISABLE_VULKAN", "1", 1);
            llama_backend_init();
            ggml_backend_load_all();
        }
    });
}

std::vector<std::string> devices() {
    initialize_backends();
    std::vector<std::string> out;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        auto device = ggml_backend_dev_get(i);
        out.emplace_back(std::string(ggml_backend_dev_name(device)) + ": " + ggml_backend_dev_description(device));
    }
    return out;
}

LocalLlm::LocalLlm(const std::string& path, bool prefer_gpu, int threads) {
    initialize_backends();
    ggml_backend_dev_t gpu = nullptr;
    if (prefer_gpu) {
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            auto device = ggml_backend_dev_get(i);
            const auto type = ggml_backend_dev_type(device);
            if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
                gpu = device;
                break;
            }
        }
    }
    // Keep partially loaded models/contexts owned until construction succeeds.
    // A Vulkan driver may throw during initialization, not merely return null.
    const auto load = [&](bool use_gpu) {
        ggml_backend_dev_t selected[] = {use_gpu ? gpu : nullptr, nullptr};
        auto params = llama_model_default_params();
        params.n_gpu_layers = use_gpu ? 99 : 0;
        params.devices = selected;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(path.c_str(), params), llama_model_free);
        if (!model) throw std::runtime_error("Не удалось загрузить GGUF-модель пунктуации");
        auto ctx = llama_context_default_params();
        ctx.n_ctx = 2048;
        ctx.n_batch = 128;
        ctx.n_ubatch = 128;
        ctx.n_threads = std::clamp(threads, 1, 4);
        ctx.n_threads_batch = ctx.n_threads;
        ctx.no_perf = true;
        ctx.offload_kqv = use_gpu;
        ctx.op_offload = use_gpu;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(llama_init_from_model(model.get(), ctx), llama_free);
        if (!context) throw std::runtime_error("Недостаточно памяти для контекста локальной LLM");
        model_ = model.release();
        context_ = context.release();
    };
    std::string failure;
    if (gpu) {
        try { load(true); }
        catch (const std::exception& error) { failure = error.what(); }
    }
    if (!model_) load(false);
    report_ = gpu && failure.empty() ? std::string("GPU ") + ggml_backend_dev_name(gpu) + " + CPU" : "CPU";
    if (prefer_gpu && !gpu) report_ += "; GPU недоступен в этой сборке или на устройстве";
    if (!failure.empty()) report_ += "; загрузка GPU не удалась: " + failure;
}

LocalLlm::~LocalLlm() {
    if (context_) llama_free(context_);
    if (model_) llama_model_free(model_);
}

std::string LocalLlm::generate(const std::string& input, int max_tokens, const std::function<bool()>& cancelled) {
    if (input.empty()) return "";
    if (input.size() > 8192 || max_tokens < 1 || max_tokens > 512) throw std::invalid_argument("Слишком длинный фрагмент для пунктуации");
    if (cancelled()) throw std::runtime_error("Обработка отменена");
    const llama_chat_message messages[] = {{"user", input.c_str()}};
    char architecture[32] = {};
    llama_model_meta_val_str(model_, "general.architecture", architecture, sizeof(architecture));
    const bool qwen35 = std::string_view(architecture) == "qwen35";
    const char* tmpl = qwen35 ? "chatml" : llama_model_chat_template(model_, nullptr);
    const int count = llama_chat_apply_template(tmpl, messages, 1, true, nullptr, 0);
    if (count <= 0) throw std::runtime_error("LLM не поддерживает шаблон диалога");
    std::string formatted(count, '\0');
    if (llama_chat_apply_template(tmpl, messages, 1, true, formatted.data(), formatted.size()) != count) {
        throw std::runtime_error("Не удалось подготовить запрос для LLM");
    }
    // Qwen 3.5's non-thinking template pre-fills an empty reasoning block.
    if (qwen35) formatted += "<think>\n\n</think>\n\n";
    const auto vocab = llama_model_get_vocab(model_);
    int token_count = -llama_tokenize(vocab, formatted.data(), formatted.size(), nullptr, 0, true, true);
    if (token_count <= 0 || token_count + max_tokens >= 2048) throw std::invalid_argument("Фрагмент превышает контекст LLM");
    std::vector<llama_token> tokens(token_count);
    if (llama_tokenize(vocab, formatted.data(), formatted.size(), tokens.data(), tokens.size(), true, true) < 0) {
        throw std::runtime_error("Не удалось разбить текст на токены");
    }
    llama_memory_clear(llama_get_memory(context_), true);
    for (int offset = 0; offset < token_count; offset += 128) {
        if (cancelled()) throw std::runtime_error("Обработка отменена");
        auto batch = llama_batch_get_one(tokens.data() + offset, std::min(128, token_count - offset));
        if (llama_decode(context_, batch) != 0) throw std::runtime_error("Ошибка вычисления локальной LLM");
    }
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
    std::string result;
    bool ended = false;
    for (int i = 0; i < max_tokens; ++i) {
        if (cancelled()) throw std::runtime_error("Обработка отменена");
        auto token = llama_sampler_sample(sampler.get(), context_, -1);
        if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
        std::vector<char> piece(128);
        int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        if (n < 0) {
            piece.resize(-n);
            n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        }
        if (n < 0) throw std::runtime_error("Ошибка декодирования токена LLM");
        result.append(piece.data(), n);
        if (result.size() > 8192) return "";
        auto batch = llama_batch_get_one(&token, 1);
        if (llama_decode(context_, batch) != 0) throw std::runtime_error("Ошибка генерации локальной LLM");
    }
    // A truncated answer is not a valid punctuation edit.
    return ended ? result : "";
}
}
