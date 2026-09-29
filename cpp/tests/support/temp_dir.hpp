#pragma once

#include <filesystem>
#include <string>

namespace stage1::testing {

// A temporary, empty directory that is recursively removed when it goes out of
// scope. Reused across the datalake tests (book-based, range-based, time-based),
// each of which needs a throwaway root to write into.
class TempDir {
public:
    explicit TempDir(const std::string& name_hint) : path_(std::filesystem::temp_directory_path() / name_hint) {
        std::filesystem::remove_all(path_);  // leftovers from a crashed previous run
        std::filesystem::create_directories(path_);
    }
    ~TempDir() { std::filesystem::remove_all(path_); }

    TempDir(const TempDir&) = delete;
    TempDir& operator=(const TempDir&) = delete;

    const std::filesystem::path& path() const { return path_; }

private:
    std::filesystem::path path_;
};

}  // namespace stage1::testing
