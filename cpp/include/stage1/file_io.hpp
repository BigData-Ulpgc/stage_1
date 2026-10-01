#pragma once

#include <filesystem>
#include <string>
#include <vector>

namespace stage1 {

// Writes `content` to `path`, creating any missing parent directories first
// (each datalake layout needs this, so it is shared instead of repeated).
// Throws std::runtime_error if a directory cannot be created or the file cannot
// be opened or written.
void write_text_file(const std::filesystem::path& path, const std::string& content);

// Reads the whole contents of `path` into a string. Throws std::runtime_error
// if the file cannot be opened.
std::string read_text_file(const std::filesystem::path& path);

// Scans `dir` (no-op if it does not exist) for every "<id>.body.txt" file
// that also has a matching "<id>.header.txt" sibling in the same directory,
// and appends each such id to `ids`. Shared by the datalake layouts whose
// files sit directly inside a folder named "<id>.body.txt"/"<id>.header.txt"
// (range, time), so they do not each reimplement the same filename parsing.
// Order is unspecified; a filename that does not parse as a plain integer
// (or has no matching header) is silently skipped.
void collect_body_header_pairs(const std::filesystem::path& dir, std::vector<int>& ids);

}  // namespace stage1
