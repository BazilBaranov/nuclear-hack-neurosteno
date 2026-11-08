#pragma once

#include <functional>
#include <memory>
#include <string>
#include <vector>

struct llama_model;
struct llama_context;

namespace neuro {
void initialize_backends();
std::vector<std::string> devices();

class LocalLlm {
public:
    LocalLlm(const std::string& path, bool prefer_gpu, int threads);
    ~LocalLlm();
    LocalLlm(const LocalLlm&) = delete;
    LocalLlm& operator=(const LocalLlm&) = delete;
    std::string generate(const std::string& input, int max_tokens, const std::function<bool()>& cancelled);
    const std::string& backend_report() const { return report_; }
private:
    llama_model* model_ = nullptr;
    llama_context* context_ = nullptr;
    std::string report_;
};
}
