#include "stage1/file_io.hpp"

#include <fstream>
#include <stdexcept>

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

}  // namespace stage1
