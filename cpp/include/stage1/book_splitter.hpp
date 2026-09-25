#pragma once

#include <optional>
#include <string>
#include <string_view>

namespace stage1 {

struct SplitBook {
    std::string header;
    std::string body;
};

// Splits the raw text of a Project Gutenberg book following section 2 of shared/SPEC.md:
//  - Line endings are normalized ("\r\n" -> "\n").
//  - header = text before the START marker, trimmed.
//  - body   = text from the end of the START marker's line up to the END marker, trimmed.
//  - The footer (from the END marker on) is discarded.
//  - Markers accept "THE" or "THIS" ("*** START OF THE/THIS PROJECT GUTENBERG EBOOK").
// Returns std::nullopt if either marker is missing (the book must then be discarded).
std::optional<SplitBook> split_book(std::string_view raw_text);

}  // namespace stage1
