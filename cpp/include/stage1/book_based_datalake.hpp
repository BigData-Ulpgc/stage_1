#pragma once

#include <filesystem>
#include <optional>

#include "stage1/datalake.hpp"

namespace stage1 {

// Book-based datalake layout (shared/SPEC.md section 3):
//   <root>/<ID>/body.txt
//   <root>/<ID>/header.txt
class BookBasedDatalake : public Datalake {
public:
    explicit BookBasedDatalake(std::filesystem::path root) : root_(std::move(root)) {}

    BookLocation write(int book_id, const std::string& header, const std::string& body) override;
    std::optional<BookLocation> locate(int book_id) const override;

private:
    // The id's paths, computed (not necessarily written yet).
    BookLocation paths_for(int book_id) const;

    std::filesystem::path root_;
};

}  // namespace stage1
