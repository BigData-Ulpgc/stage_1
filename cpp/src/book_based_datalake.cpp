#include "stage1/book_based_datalake.hpp"

#include "stage1/file_io.hpp"

namespace stage1 {

BookLocation BookBasedDatalake::write(int book_id, const std::string& header, const std::string& body) {
    const std::filesystem::path book_dir = root_ / std::to_string(book_id);
    const std::filesystem::path body_path = book_dir / "body.txt";
    const std::filesystem::path header_path = book_dir / "header.txt";

    write_text_file(body_path, body);
    write_text_file(header_path, header);

    return BookLocation{body_path.string(), header_path.string()};
}

}  // namespace stage1
