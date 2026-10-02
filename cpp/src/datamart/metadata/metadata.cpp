#include "stage1/datamart/metadata/metadata.hpp"

#include <regex>

#include "stage1/util/text_utils.hpp"

namespace stage1 {

namespace {

// ^ and $ match the start/end of each line (not of the whole header), and '.'
// never crosses a line, which is exactly what "first line of the value" needs.
constexpr auto kFlags = std::regex::ECMAScript | std::regex::multiline;

// Runs `pattern` (must have exactly one capturing group) against `header` and
// returns its first match, trimmed; nullopt if the pattern does not match at all.
std::optional<std::string> extract_field(std::string_view header, const std::regex& pattern) {
    const char* begin = header.data();
    const char* end = begin + header.size();

    std::cmatch match;
    if (!std::regex_search(begin, end, match, pattern)) {
        return std::nullopt;
    }

    const std::string_view captured(match[1].first, static_cast<std::size_t>(match[1].length()));
    return std::string(trim(captured));
}

}  // namespace

BookMetadata extract_metadata(std::string_view header) {
    static const std::regex title_pattern(R"(^Title:\s*(.+)$)", kFlags);
    static const std::regex author_pattern(R"(^Author:\s*(.+)$)", kFlags);
    static const std::regex release_date_pattern(R"(^Release date:\s*(.+?)(?:\s*\[.*)?$)", kFlags);
    static const std::regex language_pattern(R"(^Language:\s*(.+)$)", kFlags);

    return BookMetadata{
        extract_field(header, title_pattern),
        extract_field(header, author_pattern),
        extract_field(header, release_date_pattern),
        extract_field(header, language_pattern),
    };
}

}  // namespace stage1
