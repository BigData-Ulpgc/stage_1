#pragma once

#include <filesystem>
#include <optional>
#include <string>

#include "stage1/metadata.hpp"

// Forward declaration: only metadata_store.cpp needs the full sqlite3 type, so the
// rest of the project including this header does not pull in <sqlite3.h>.
struct sqlite3;

namespace stage1 {

// A book row as stored in (and read back from) the metadata database
// (shared/SPEC.md section 4: the `books` table).
struct StoredBook {
    int book_id;
    std::optional<std::string> title;
    std::optional<std::string> author;
    std::optional<std::string> language;
    std::optional<std::string> release_date;
    std::string body_path;
    std::string header_path;
};

// Owns a connection to the SQLite metadata database and creates the `books`
// table and its indexes (SPEC section 4) the first time it is opened.
class MetadataStore {
public:
    explicit MetadataStore(const std::filesystem::path& db_path);
    ~MetadataStore();

    MetadataStore(const MetadataStore&) = delete;
    MetadataStore& operator=(const MetadataStore&) = delete;

    // Inserts a book, or replaces its row if `book_id` already exists.
    void insert_book(int book_id, const BookMetadata& metadata, const std::string& body_path,
                      const std::string& header_path);

    // Looks up a book by id. Returns std::nullopt if no such book is stored.
    std::optional<StoredBook> find_by_id(int book_id) const;

    // Groups every insert_book() call between begin_transaction() and
    // commit_transaction() into a single disk commit, instead of each
    // insert_book() committing on its own (SQLite's default, "autocommit"
    // behavior). Inserting many rows one by one without this pays one fsync
    // per row, which Entry 36's benchmark run showed as noisy, lower
    // throughput; see Entry 37 for the measured before/after. rollback_
    // transaction() discards everything written since begin_transaction()
    // instead of committing it.
    void begin_transaction();
    void commit_transaction();
    void rollback_transaction();

private:
    sqlite3* db_;
};

}  // namespace stage1
