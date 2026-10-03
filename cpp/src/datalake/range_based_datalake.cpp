#include "stage1/datalake/range_based_datalake.hpp"

#include <cstdio>

#include "stage1/util/file_io.hpp"

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

BookLocation RangeBasedDatalake::paths_for(int book_id) const {
    const std::filesystem::path range_dir = root_ / range_folder_name(book_id);
    const std::string id = std::to_string(book_id);
    return BookLocation{(range_dir / (id + ".body.txt")).string(), (range_dir / (id + ".header.txt")).string()};
}

BookLocation RangeBasedDatalake::write(int book_id, const std::string& header, const std::string& body) {
    const BookLocation location = paths_for(book_id);
    write_text_file(location.body_path, body);
    write_text_file(location.header_path, header);
    return location;
}

std::optional<BookLocation> RangeBasedDatalake::locate(int book_id) const {
    // Pure computation, same reasoning as write(): the range folder is a
    // function of the id alone, no lookup table needed to find it again.
    const BookLocation location = paths_for(book_id);
    if (!std::filesystem::exists(location.body_path) || !std::filesystem::exists(location.header_path)) {
        return std::nullopt;
    }
    return location;
}

std::vector<int> RangeBasedDatalake::list_book_ids() const {
    std::vector<int> ids;
    if (!std::filesystem::exists(root_)) {
        return ids;
    }
    for (const auto& range_dir : std::filesystem::directory_iterator(root_)) {
        if (range_dir.is_directory()) {
            collect_body_header_pairs(range_dir.path(), ids);
        }
    }
    return ids;
}

}  // namespace stage1
