#include "stage1/book_based_datalake.hpp"

#include <charconv>

#include "stage1/file_io.hpp"

namespace stage1 {

BookLocation BookBasedDatalake::paths_for(int book_id) const {
    const std::filesystem::path book_dir = root_ / std::to_string(book_id);
    return BookLocation{(book_dir / "body.txt").string(), (book_dir / "header.txt").string()};
}

BookLocation BookBasedDatalake::write(int book_id, const std::string& header, const std::string& body) {
    const BookLocation location = paths_for(book_id);
    write_text_file(location.body_path, body);
    write_text_file(location.header_path, header);
    return location;
}

std::optional<BookLocation> BookBasedDatalake::locate(int book_id) const {
    // Pure computation, same reasoning as write(): the path never depended on
    // anything but the id, so no lookup table is needed to find it again.
    const BookLocation location = paths_for(book_id);
    if (!std::filesystem::exists(location.body_path) || !std::filesystem::exists(location.header_path)) {
        return std::nullopt;
    }
    return location;
}

std::vector<int> BookBasedDatalake::list_book_ids() const {
    std::vector<int> ids;
    if (!std::filesystem::exists(root_)) {
        return ids;
    }

    for (const auto& entry : std::filesystem::directory_iterator(root_)) {
        if (!entry.is_directory()) {
            continue;  // every book gets its own directory, named after its id
        }
        const std::string name = entry.path().filename().string();
        int book_id = 0;
        const auto result = std::from_chars(name.data(), name.data() + name.size(), book_id);
        if (result.ec != std::errc{} || result.ptr != name.data() + name.size()) {
            continue;  // not a plain integer directory name
        }
        if (std::filesystem::exists(entry.path() / "body.txt") && std::filesystem::exists(entry.path() / "header.txt")) {
            ids.push_back(book_id);
        }
    }
    return ids;
}

}  // namespace stage1
