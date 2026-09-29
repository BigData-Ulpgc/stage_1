#include "stage1/stopwords.hpp"

#include <fstream>
#include <stdexcept>

#include "stage1/text_utils.hpp"

namespace stage1 {

std::unordered_set<std::string> load_stopwords(const std::filesystem::path& path) {
    std::ifstream file(path);
    if (!file) {
        throw std::runtime_error("cannot open stopwords file: " + path.string());
    }

    std::unordered_set<std::string> stopwords;
    std::string line;
    while (std::getline(file, line)) {
        const std::string_view word = trim(line);
        if (word.empty() || word.front() == '#') {
            continue;
        }
        stopwords.emplace(word);
    }
    return stopwords;
}

}  // namespace stage1
