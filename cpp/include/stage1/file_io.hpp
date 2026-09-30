#pragma once

#include <filesystem>
#include <string>

namespace stage1 {

// Writes `content` to `path`, creating any missing parent directories first
// (each datalake layout needs this, so it is shared instead of repeated).
// Throws std::runtime_error if a directory cannot be created or the file cannot
// be opened or written.
void write_text_file(const std::filesystem::path& path, const std::string& content);

// Reads the whole contents of `path` into a string. Throws std::runtime_error
// if the file cannot be opened.
std::string read_text_file(const std::filesystem::path& path);

}  // namespace stage1
