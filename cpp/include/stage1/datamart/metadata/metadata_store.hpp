#pragma once

#include <filesystem>
#include <optional>
#include <string>
#include <vector>

#include "stage1/datamart/metadata/metadata.hpp"

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

    // Inserts (or replaces) every row of `rows` as one batch, the Java
    // module's saveAll: one transaction and one prepared statement for the
    // whole batch. All or nothing: if a row fails, the batch is rolled back
    // and the error rethrown. An empty batch does nothing.
    void insert_books(const std::vector<StoredBook>& rows);

    // How many rows the `books` table holds.
    long long count() const;

    // Looks up a book by id. Returns std::nullopt if no such book is stored.
    std::optional<StoredBook> find_by_id(int book_id) const;

    // Every book whose author/title is exactly `author`/`title` (empty if
    // none), in ascending book_id order, the order the Java module's queries
    // return. Exact match, not a substring search -- what SPEC section 4's
    // "find all books by a specific author" means, and what the author/title
    // indexes this class already creates (see the constructor) exist for;
    // nothing called either of these until DEVLOG entry 38.
    std::vector<StoredBook> find_by_author(const std::string& author) const;
    std::vector<StoredBook> find_by_title(const std::string& title) const;

private:
    // The transaction insert_books() wraps each batch in. One commit per batch
    // instead of one per row (SQLite's autocommit) is what made bulk inserts
    // fast (DEVLOG Entries 36 and 37); rollback discards the whole batch.
    void begin_transaction();
    void commit_transaction();
    void rollback_transaction();

    sqlite3* db_;
};

}  // namespace stage1
