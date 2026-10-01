#include "stage1/file_io.hpp"

#include <charconv>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <string_view>

namespace stage1 {

void write_text_file(const std::filesystem::path& path, const std::string& content) {
    std::error_code error;
    std::filesystem::create_directories(path.parent_path(), error);
    if (error) {
        throw std::runtime_error("cannot create directory " + path.parent_path().string() + ": " + error.message());
    }

    std::ofstream file(path, std::ios::binary);
    if (!file) {
        throw std::runtime_error("cannot open file for writing: " + path.string());
    }
    file << content;
    if (!file) {
        throw std::runtime_error("failed writing file: " + path.string());
    }
}

std::string read_text_file(const std::filesystem::path& path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) {
        throw std::runtime_error("cannot open file for reading: " + path.string());
    }
    std::ostringstream contents;
    contents << file.rdbuf();
    return contents.str();
}

void collect_body_header_pairs(const std::filesystem::path& dir, std::vector<int>& ids) {
    constexpr std::string_view kBodySuffix = ".body.txt";
    if (!std::filesystem::exists(dir)) {
        return;
    }

    for (const auto& entry : std::filesystem::directory_iterator(dir)) {
        if (!entry.is_regular_file()) {
            continue;
        }
        const std::string filename = entry.path().filename().string();
        if (filename.size() <= kBodySuffix.size() ||
            filename.compare(filename.size() - kBodySuffix.size(), kBodySuffix.size(), kBodySuffix) != 0) {
            continue;  // not a "<something>.body.txt" file
        }

        const std::string_view id_text(filename.data(), filename.size() - kBodySuffix.size());
        int book_id = 0;
        const auto result = std::from_chars(id_text.data(), id_text.data() + id_text.size(), book_id);
        if (result.ec != std::errc{} || result.ptr != id_text.data() + id_text.size()) {
            continue;  // the part before ".body.txt" is not a plain integer
        }

        if (std::filesystem::exists(dir / (std::string(id_text) + ".header.txt"))) {
            ids.push_back(book_id);
        }
    }
}

}  // namespace stage1
