#include "stage1/book_splitter.hpp"

#include <initializer_list>

#include "stage1/text_utils.hpp"

namespace stage1 {

namespace {

constexpr std::string_view kStartTheMarker = "*** START OF THE PROJECT GUTENBERG EBOOK";
constexpr std::string_view kStartThisMarker = "*** START OF THIS PROJECT GUTENBERG EBOOK";
constexpr std::string_view kEndTheMarker = "*** END OF THE PROJECT GUTENBERG EBOOK";
constexpr std::string_view kEndThisMarker = "*** END OF THIS PROJECT GUTENBERG EBOOK";

// Replaces every "\r\n" with "\n".
std::string normalize_newlines(std::string_view text) {
    std::string normalized;
    normalized.reserve(text.size());
    for (std::size_t i = 0; i < text.size(); ++i) {
        const bool is_cr_of_crlf = text[i] == '\r' && i + 1 < text.size() && text[i + 1] == '\n';
        if (!is_cr_of_crlf) {
            normalized.push_back(text[i]);
        }
    }
    return normalized;
}

// Position of the earliest marker of the list found at or after `from`.
std::optional<std::size_t> find_first_marker(std::string_view text, std::size_t from,
                                             std::initializer_list<std::string_view> markers) {
    std::optional<std::size_t> earliest;
    for (const std::string_view marker : markers) {
        const auto position = text.find(marker, from);
        if (position != std::string_view::npos && (!earliest || position < *earliest)) {
            earliest = position;
        }
    }
    return earliest;
}

}  // namespace

std::optional<SplitBook> split_book(std::string_view raw_text) {
    const std::string text = normalize_newlines(raw_text);

    const auto start = find_first_marker(text, 0, {kStartTheMarker, kStartThisMarker});
    if (!start) {
        return std::nullopt;
    }

    // The body begins on the line after the START marker (that line holds the book title).
    const auto end_of_start_line = text.find('\n', *start);
    const std::size_t body_begin = end_of_start_line == std::string::npos ? text.size() : end_of_start_line + 1;

    const auto end = find_first_marker(text, body_begin, {kEndTheMarker, kEndThisMarker});
    if (!end) {
        return std::nullopt;
    }

    const std::string_view view = text;
    return SplitBook{
        std::string(trim(view.substr(0, *start))),
        std::string(trim(view.substr(body_begin, *end - body_begin))),
    };
}

}  // namespace stage1
