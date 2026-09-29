#include "stage1/range_based_datalake.hpp"

#include <cstdio>

#include "stage1/file_io.hpp"

namespace stage1 {

namespace {

// Formats `value` as a 5-digit, zero-padded decimal string (e.g. 1000 -> "01000").
std::string zero_pad5(int value) {
    char buffer[6];  // 5 digits + the trailing '\0' snprintf always writes
    std::snprintf(buffer, sizeof(buffer), "%05d", value);
    return std::string(buffer);
}

}  // namespace

std::string range_folder_name(int book_id) {
    const int range_start = (book_id / 1000) * 1000;
    const int range_end = range_start + 999;
    return zero_pad5(range_start) + "-" + zero_pad5(range_end);
}

BookLocation RangeBasedDatalake::write(int book_id, const std::string& header, const std::string& body) {
    const std::filesystem::path range_dir = root_ / range_folder_name(book_id);
    const std::string id = std::to_string(book_id);
    const std::filesystem::path body_path = range_dir / (id + ".body.txt");
    const std::filesystem::path header_path = range_dir / (id + ".header.txt");

    write_text_file(body_path, body);
    write_text_file(header_path, header);

    return BookLocation{body_path.string(), header_path.string()};
}

}  // namespace stage1
