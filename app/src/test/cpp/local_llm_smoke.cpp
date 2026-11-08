#include "local_llm.h"
#include <iostream>
#include <stdexcept>

int main(int argc, char** argv) {
    if (argc != 2) throw std::invalid_argument("Pass the pinned Qwen GGUF path");
    neuro::LocalLlm model(argv[1], 2);
    bool cancelled = false;
    int cancellation_checks = 0;
    try { model.generate("Count from 1 to 1000", 16, [&] { return ++cancellation_checks >= 4; }); }
    catch (const std::runtime_error&) { cancelled = true; }
    if (!cancelled) throw std::runtime_error("Cancellation ignored");
    const auto answer = model.generate(
        "Add punctuation to the following Russian text. Return the same words with commas, periods, and question marks.\nпривет как дела",
        64, [] { return false; });
    if (answer != "Привет, как дела?") throw std::runtime_error("Russian punctuation fixture failed: " + answer);
    std::cout << "backend=CPU\nanswer=" << answer << "\n";
    const auto second = model.generate("Return exactly: OK", 16, [] { return false; });
    if (second.find("OK") == std::string::npos) throw std::runtime_error("Context reset or second generation failed");
    const auto english = model.generate(
        "Add punctuation to the following English text. Return the same words with commas, periods, and question marks.\nhello how are you",
        64, [] { return false; });
    if (english != "Hello, how are you?") throw std::runtime_error("English punctuation fixture failed: " + english);
    std::cout << "second=" << second << "\nenglish=" << english << "\nLLM_SMOKE_PASSED\n";
}
