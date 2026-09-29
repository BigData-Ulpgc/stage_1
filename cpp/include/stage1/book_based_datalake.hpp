#pragma once

#include <filesystem>

#include "stage1/datalake.hpp"

namespace stage1 {

// Book-based datalake layout (shared/SPEC.md section 3):
//   <root>/<ID>/body.txt
//   <root>/<ID>/header.txt
class BookBasedDatalake : public Datalake {
public:
    explicit BookBasedDatalake(std::filesystem::path root) : root_(std::move(root)) {}

    BookLocation write(int book_id, const std::string& header, const std::string& body) override;

private:
    std::filesystem::path root_;
};

}  // namespace stage1
