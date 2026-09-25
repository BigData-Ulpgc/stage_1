#pragma once

#include <string_view>

namespace stage1 {

// Returns `text` without leading and trailing whitespace (space, \t, \r, \n, \f, \v).
// The result is a view into `text`: it must not outlive the original string.
inline std::string_view trim(std::string_view text) {
    constexpr std::string_view kWhitespace = " \t\r\n\f\v";
    const auto first = text.find_first_not_of(kWhitespace);
    if (first == std::string_view::npos) {
        return {};  // empty or only whitespace
    }
    const auto last = text.find_last_not_of(kWhitespace);
    return text.substr(first, last - first + 1);
}

}  // namespace stage1
