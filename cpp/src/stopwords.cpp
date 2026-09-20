#include "stage1/stopwords.hpp"

#include <fstream>
#include <stdexcept>
#include <utility>

namespace stage1 {

namespace {

std::string trim(const std::string& text) {
    constexpr const char* kWhitespace = " \t\r\n";
    const auto first = text.find_first_not_of(kWhitespace);
    if (first == std::string::npos) {
        return "";  // the line is empty or only whitespace
    }
    const auto last = text.find_last_not_of(kWhitespace);
    return text.substr(first, last - first + 1);
}

}  // namespace

std::unordered_set<std::string> load_stopwords(const std::filesystem::path& path) {
    std::ifstream file(path);
    if (!file) {
        throw std::runtime_error("cannot open stopwords file: " + path.string());
    }

    std::unordered_set<std::string> stopwords;
    std::string line;
    while (std::getline(file, line)) {
        std::string word = trim(line);
        if (word.empty() || word[0] == '#') {
            continue;
        }
        stopwords.insert(std::move(word));
    }
    return stopwords;
}

}  // namespace stage1
