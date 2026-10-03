#pragma once

#include <cstddef>
#include <filesystem>
#include <memory>
#include <string>
#include <vector>

#include "stage1/datamart/metadata/metadata_store.hpp"

namespace stage1 {

// What the metadata experiments share, ported from the Java module's
// MetadataBenchmark so that both languages run them under the same conditions.

// The variants compared, in Java's order: SPEC section 4's schema, and the
// same schema without idx_books_author and idx_books_title, to see what those
// two indexes contribute.
inline const std::vector<std::string> kMetadataStructures = {"sqlite", "sqlite_no_index"};

// SPEC section 10.2: N = 1,000, 10,000 and 100,000 rows.
inline constexpr std::size_t kMetadataSizes[] = {1000, 10000, 100000};

// Java's DEFAULT_BATCH_SIZE (rows per transaction in metadata_insert) and
// DEFAULT_QUERIES (queries of each type in metadata_query).
inline constexpr std::size_t kMetadataBatchSize = 1000;
inline constexpr int kMetadataQueries = 1000;

// The synthetic rows of SPEC section 10.2 (Java's syntheticDataset), for
// i = 0 ... size-1: book_id i+1, title "Title <i/2>", author "Author <i/10>"
// (integer division), language "English", release_date "January 1, 2000",
// paths datalake/book/<id>/body.txt and header.txt. Every author has 10 books
// and every title 2, whatever the size, and a smaller size is always a prefix
// of a larger one: as N grows, only the table grows, not each answer.
std::vector<StoredBook> synthetic_metadata(std::size_t size);

// Java's freshRepository: deletes `db` and its -journal, then opens an empty
// MetadataStore there. For "sqlite_no_index" it then drops the two indexes.
// Always called in an untimed setup, after the previous store was closed.
// Throws std::invalid_argument for a structure not in kMetadataStructures.
std::unique_ptr<MetadataStore> fresh_metadata_store(const std::string& structure, const std::filesystem::path& db);

// Java's dropAuthorAndTitleIndexes: removes idx_books_author and
// idx_books_title from the database file `db`, through a connection of its
// own, as Java does. Throws std::runtime_error if SQLite fails.
void drop_author_and_title_indexes(const std::filesystem::path& db);

}  // namespace stage1
