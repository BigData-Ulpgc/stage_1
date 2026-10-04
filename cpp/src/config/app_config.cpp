#include "stage1/config/app_config.hpp"

#include <algorithm>
#include <stdexcept>
#include <vector>

#include "stage1/config/datalake_factory.hpp"
#include "stage1/config/index_factory.hpp"
#include "stage1/util/file_io.hpp"
#include "stage1/util/text_utils.hpp"

namespace stage1 {

namespace {

constexpr const char* kDatalakeKey = "datalake.structure";
constexpr const char* kIndexKey = "index.structure";

void require_one_of(const std::string& key, const std::string& value, const std::vector<std::string>& valid) {
    if (std::find(valid.begin(), valid.end(), value) == valid.end()) {
        std::string options;
        for (const auto& option : valid) {
            options += (options.empty() ? "" : ", ") + option;
        }
        throw std::invalid_argument(key + " = \"" + value + "\" does not exist. Options: " + options);
    }
}

}  // namespace

Properties parse_properties(std::string_view text) {
    Properties properties;
    std::size_t line_number = 0;
    std::size_t start = 0;
    while (start <= text.size()) {
        const std::size_t end = std::min(text.find('\n', start), text.size());
        const std::string line(trim(text.substr(start, end - start)));
        ++line_number;
        start = end + 1;
        if (line.empty() || line.front() == '#' || line.front() == '!') {
            continue;
        }
        const std::size_t equals = line.find('=');
        if (equals == std::string::npos) {
            throw std::invalid_argument("line " + std::to_string(line_number) + " is not \"key = value\": " + line);
        }
        properties[std::string(trim(line.substr(0, equals)))] = std::string(trim(line.substr(equals + 1)));
    }
    return properties;
}

AppConfig load_config(const std::filesystem::path& file, const Properties& overrides) {
    Properties merged;
    if (std::filesystem::exists(file)) {
        merged = parse_properties(read_text_file(file));
    }
    for (const auto& [key, value] : overrides) {
        merged[key] = value;  // -Dkey=value wins over the file
    }

    AppConfig config;
    for (const auto& [key, value] : merged) {
        if (key == kDatalakeKey) {
            config.datalake_structure = value;
        } else if (key == kIndexKey) {
            config.index_structure = value;
        } else {
            throw std::invalid_argument("unknown configuration key: " + key + ". Valid keys: " + kDatalakeKey +
                                        ", " + kIndexKey);
        }
    }
    require_one_of(kDatalakeKey, config.datalake_structure, kDatalakeStructures);
    require_one_of(kIndexKey, config.index_structure, kIndexStructures);
    return config;
}

std::string describe(const AppConfig& config) {
    return std::string(kDatalakeKey) + " = " + config.datalake_structure + "\n" + kIndexKey + " = " +
           config.index_structure + "\n";
}

}  // namespace stage1
