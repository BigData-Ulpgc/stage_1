#pragma once

#include <filesystem>
#include <optional>
#include <string>

#include "stage1/datalake.hpp"

namespace stage1 {

// Name of the range folder a book belongs to (shared/SPEC.md section 3):
// INI = (book_id / 1000) * 1000, FIN = INI + 999, both zero-padded to 5 digits
// (e.g. book_id 1342 -> "01000-01999"). Pure function, no filesystem access;
// assumes book_id is a positive Gutenberg id, as every id in this project is.
std::string range_folder_name(int book_id);

// Range-based datalake layout (shared/SPEC.md section 3):
//   <root>/<INI>-<FIN>/<ID>.body.txt
//   <root>/<INI>-<FIN>/<ID>.header.txt
class RangeBasedDatalake : public Datalake {
public:
    explicit RangeBasedDatalake(std::filesystem::path root) : root_(std::move(root)) {}

    BookLocation write(int book_id, const std::string& header, const std::string& body) override;
    std::optional<BookLocation> locate(int book_id) const override;

private:
    BookLocation paths_for(int book_id) const;

    std::filesystem::path root_;
};

}  // namespace stage1
