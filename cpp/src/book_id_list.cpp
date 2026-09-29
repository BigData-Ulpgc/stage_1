#include "stage1/book_id_list.hpp"

#include <charconv>
#include <fstream>
#include <stdexcept>

#include "stage1/text_utils.hpp"

namespace stage1 {

std::vector<int> load_book_ids(const std::filesystem::path& path) {
    std::ifstream file(path);
    if (!file) {
        throw std::runtime_error("cannot open book id list: " + path.string());
    }

    std::vector<int> ids;
    std::string line;
    while (std::getline(file, line)) {
        const std::string_view trimmed = trim(line);
        if (trimmed.empty() || trimmed.front() == '#') {
            continue;
        }
        int book_id = 0;
        const auto result = std::from_chars(trimmed.data(), trimmed.data() + trimmed.size(), book_id);
        if (result.ec == std::errc{}) {
            ids.push_back(book_id);
        }
    }
    return ids;
}

}  // namespace stage1
