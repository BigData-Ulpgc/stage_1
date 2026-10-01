#include "stage1/index_readers.hpp"

#include <fstream>
#include <memory>
#include <stdexcept>

#include <nlohmann/json.hpp>

#include "stage1/hierarchical_index_writer.hpp"

namespace stage1 {

std::function<std::vector<int>(const std::string&)> monolithic_postings_fetcher(
    const std::filesystem::path& path) {
    std::ifstream file(path);
    if (!file) {
        throw std::runtime_error("cannot open index file for reading: " + path.string());
    }
    // Held through a shared_ptr so that copying the returned function (which
    // std::function is free to do) never copies the whole parsed index.
    auto document = std::make_shared<nlohmann::json>(nlohmann::json::parse(file));

    return [document](const std::string& term) -> std::vector<int> {
        const auto it = document->find(term);
        if (it == document->end()) {
            return {};
        }
        return it->get<std::vector<int>>();
    };
}

std::function<std::vector<int>(const std::string&)> hierarchical_postings_fetcher(
    const std::filesystem::path& root) {
    return [root](const std::string& term) -> std::vector<int> {
        const std::filesystem::path path = root / hierarchical_folder_name(term) / (term + ".txt");
        std::ifstream file(path);
        if (!file) {
            return {};
        }
        std::vector<int> postings;
        int book_id = 0;
        while (file >> book_id) {
            postings.push_back(book_id);
        }
        return postings;
    };
}

}  // namespace stage1
