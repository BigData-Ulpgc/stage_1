#pragma once

#include <filesystem>
#include <map>
#include <string>
#include <string_view>

namespace stage1 {

// The structures the pipeline and `search` use, chosen without touching code:
// the two keys of the Java module's config.properties that choose a structure
// (AppConfig.DATALAKE_STRUCTURE and INDEX_STRUCTURE), with the same names and
// the same defaults. Benchmarks do not use them: they always compare every
// structure.
struct AppConfig {
    std::string datalake_structure = "time";     // book | range | time
    std::string index_structure = "monolithic";  // monolithic | hierarchical | mongo
};

// "key = value" pairs, as read from a .properties file or from -Dkey=value.
using Properties = std::map<std::string, std::string>;

// Parses the subset of Java's .properties format this module needs: one
// "key = value" per line, spaces around both trimmed. Blank lines and lines
// starting with '#' or '!' are comments. Throws std::invalid_argument, naming
// the line, for any other line without '='.
Properties parse_properties(std::string_view text);

// Java's AppConfig.load: the defaults, then `file` if it exists, then
// `overrides` (the -Dkey=value arguments); later ones win. Throws
// std::invalid_argument for an unknown key or a structure that does not
// exist, so a typo stops the program at startup, not half-way.
AppConfig load_config(const std::filesystem::path& file, const Properties& overrides);

// One line per key with its effective value, for the `config` command.
std::string describe(const AppConfig& config);

}  // namespace stage1
