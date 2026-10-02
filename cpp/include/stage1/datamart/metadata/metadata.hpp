#pragma once

#include <optional>
#include <string>
#include <string_view>

namespace stage1 {

// A book's structured metadata, parsed from its Gutenberg header (SPEC section 4).
// A field is std::nullopt when its line is missing from the header; that is how
// the datamart writer (a later step) will know to store SQL NULL instead of a value.
struct BookMetadata {
    std::optional<std::string> title;
    std::optional<std::string> author;
    std::optional<std::string> release_date;
    std::optional<std::string> language;
};

// Extracts title, author, release date and language from an already header/body-split
// book (see split_book), using the regexes in shared/SPEC.md section 4: first match,
// only the first line of the value, trimmed. Assumes "\r\n" was already normalized to
// "\n", which split_book already guarantees.
BookMetadata extract_metadata(std::string_view header);

}  // namespace stage1
