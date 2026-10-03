#pragma once

#include <filesystem>
#include <string>
#include <unordered_set>

namespace stage1 {

// Loads a stopword file (one word per line, see shared/SPEC.md section 1).
// Lines that are empty or start with '#' are ignored, and surrounding whitespace
// (including a trailing '\r' from Windows line endings) is trimmed.
// Throws std::runtime_error if the file cannot be opened.
std::unordered_set<std::string> load_stopwords(const std::filesystem::path& path);

}  // namespace stage1
