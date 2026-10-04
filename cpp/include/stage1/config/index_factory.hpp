#pragma once

#include <filesystem>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "stage1/datamart/index/index_writer.hpp"

namespace stage1 {

// The inverted-index structures of SPEC section 6, in the order of the
// benchmark CSV rows. (Java's InvertedIndexFactory also has "memory", an
// index that is never persisted; here the in-memory InvertedIndex is not a
// structure of its own.)
inline const std::vector<std::string> kIndexStructures = {"monolithic", "hierarchical", "mongo"};

// Where each file-based structure lives under a data folder (SPEC section 6,
// the same paths as Java's AppConfig): <data>/datamarts/inverted_index.json
// and <data>/datamarts/inverted_index/. "mongo" lives in SPEC section 6's
// database and collection, on the group's server.
std::filesystem::path monolithic_index_path(const std::filesystem::path& data_dir);
std::filesystem::path hierarchical_index_path(const std::filesystem::path& data_dir);

// Java's InvertedIndexFactory, write side: the writer of `structure` for the
// index under `data_dir`. Throws std::invalid_argument for an unknown
// structure.
std::unique_ptr<IndexWriter> create_index_writer(const std::string& structure, const std::filesystem::path& data_dir);

// The read side: a postings fetcher of `structure` under `data_dir`, the
// function query_and takes. Throws std::invalid_argument for an unknown
// structure.
std::function<std::vector<int>(const std::string&)> open_index(const std::string& structure,
                                                               const std::filesystem::path& data_dir);

// Whether `structure` has been written under `data_dir`: monolithic's file,
// hierarchical's folder, or mongo's collection. Throws std::runtime_error for
// "mongo" when no server is reachable, and std::invalid_argument for an
// unknown structure.
bool index_exists(const std::string& structure, const std::filesystem::path& data_dir);

// How many terms the stored `structure` under `data_dir` holds: monolithic's
// JSON keys, hierarchical's term files, or mongo's documents; 0 if it was
// never written. The pipeline compares it with the index it rebuilds at
// startup, to find a structure that is missing, partial or stale. Throws
// std::runtime_error for "mongo" when no server is reachable.
std::size_t stored_term_count(const std::string& structure, const std::filesystem::path& data_dir);

}  // namespace stage1
