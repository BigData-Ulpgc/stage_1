#include "stage1/query_list.hpp"

#include <fstream>
#include <stdexcept>

#include "stage1/text_utils.hpp"

namespace stage1 {

std::vector<std::string> load_queries(const std::filesystem::path& path) {
    std::ifstream file(path);
    if (!file) {
        throw std::runtime_error("cannot open query list: " + path.string());
    }

    std::vector<std::string> queries;
    std::string line;
    while (std::getline(file, line)) {
        const std::string_view trimmed = trim(line);
        if (trimmed.empty() || trimmed.front() == '#') {
            continue;
        }
        queries.emplace_back(trimmed);
    }
    return queries;
}

}  // namespace stage1
