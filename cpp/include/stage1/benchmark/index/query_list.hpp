#pragma once

#include <filesystem>
#include <string>
#include <vector>

namespace stage1 {

// Loads the benchmark query workload (shared/queries.txt, shared/SPEC.md
// section 1): one query per line, in file order. Lines that are empty or
// start with '#' are ignored. Throws std::runtime_error if the file cannot
// be opened.
std::vector<std::string> load_queries(const std::filesystem::path& path);

}  // namespace stage1
