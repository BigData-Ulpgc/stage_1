#pragma once

#include <filesystem>
#include <vector>

namespace stage1 {

// Loads the dataset's book ids (shared/book_ids.txt, shared/SPEC.md section 1):
// one id per line, in file order (order matters: "mismo orden en los tres
// lenguajes"). Lines that are empty or start with '#' are ignored.
// Throws std::runtime_error if the file cannot be opened.
std::vector<int> load_book_ids(const std::filesystem::path& path);

}  // namespace stage1
