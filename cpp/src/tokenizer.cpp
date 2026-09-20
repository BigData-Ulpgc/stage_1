#include "stage1/tokenizer.hpp"

#include <utility>

namespace stage1 {

namespace {

constexpr std::size_t kMinTokenLength = 2;

bool is_token_char(char c) {
    return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
}

}  // namespace

std::vector<std::string> tokenize(std::string_view text) {
    std::vector<std::string> tokens;
    std::string current;

    // Closes the token being built: keeps it if long enough, then starts a new one.
    auto flush = [&]() {
        if (current.size() >= kMinTokenLength) {
            tokens.push_back(std::move(current));
        }
        current.clear();
    };

    for (char c : text) {
        if (c >= 'A' && c <= 'Z') {
            c = static_cast<char>(c - 'A' + 'a');
        }
        if (is_token_char(c)) {
            current.push_back(c);
        } else {
            flush();
        }
    }
    flush();  // the text may end in the middle of a token

    return tokens;
}

}  // namespace stage1
