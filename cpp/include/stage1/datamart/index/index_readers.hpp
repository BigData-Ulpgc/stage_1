#pragma once

#include <filesystem>
#include <functional>
#include <string>
#include <vector>

namespace stage1 {

// Readers for the on-disk index structures the IndexWriter implementations
// produce (shared/SPEC.md section 6). Each returns a postings-fetcher -- the
// "what are this term's postings?" function query_and() accepts -- so a query
// can run straight against a persisted structure instead of an in-memory
// InvertedIndex rebuilt from the books. mongo_postings_fetcher
// (mongo_index_writer.hpp) is the equivalent for the third structure.

// Parses the single JSON file MonolithicIndexWriter wrote at `path` once, up
// front, then answers every lookup from the parsed document. Throws if the
// file cannot be opened or is not valid JSON.
std::function<std::vector<int>(const std::string&)> monolithic_postings_fetcher(
    const std::filesystem::path& path);

// Reads the layout HierarchicalIndexWriter wrote under `root`
// (<root>/<FIRST-LETTER>/<term>.txt) on demand, one file per lookup: nothing
// is loaded up front, so that per-term file access is this structure's whole
// query cost. A term with no file has no postings (empty result).
std::function<std::vector<int>(const std::string&)> hierarchical_postings_fetcher(
    const std::filesystem::path& root);

}  // namespace stage1
