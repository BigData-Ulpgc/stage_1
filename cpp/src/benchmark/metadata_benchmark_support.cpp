#include "stage1/benchmark/metadata_benchmark_support.hpp"

#include <sqlite3.h>

#include <stdexcept>

namespace stage1 {

std::vector<StoredBook> synthetic_metadata(std::size_t size) {
    std::vector<StoredBook> rows;
    rows.reserve(size);
    for (std::size_t i = 0; i < size; ++i) {
        const std::string id = std::to_string(i + 1);
        rows.push_back(StoredBook{static_cast<int>(i + 1), "Title " + std::to_string(i / 2),
                                  "Author " + std::to_string(i / 10), "English", "January 1, 2000",
                                  "datalake/book/" + id + "/body.txt", "datalake/book/" + id + "/header.txt"});
    }
    return rows;
}

std::unique_ptr<MetadataStore> fresh_metadata_store(const std::string& structure, const std::filesystem::path& db) {
    if (structure != "sqlite" && structure != "sqlite_no_index") {
        throw std::invalid_argument("unknown metadata structure: " + structure);
    }
    std::filesystem::remove(db);
    std::filesystem::remove(db.string() + "-journal");
    auto store = std::make_unique<MetadataStore>(db);  // creates the table and both indexes
    if (structure == "sqlite_no_index") {
        drop_author_and_title_indexes(db);
    }
    return store;
}

void drop_author_and_title_indexes(const std::filesystem::path& db) {
    sqlite3* connection = nullptr;
    if (sqlite3_open(db.string().c_str(), &connection) != SQLITE_OK) {
        const std::string message = sqlite3_errmsg(connection);
        sqlite3_close(connection);
        throw std::runtime_error("cannot open " + db.string() + ": " + message);
    }
    char* error = nullptr;
    const int result = sqlite3_exec(connection,
                                    "DROP INDEX IF EXISTS idx_books_author;"
                                    "DROP INDEX IF EXISTS idx_books_title;",
                                    nullptr, nullptr, &error);
    const std::string message = error ? error : "";
    sqlite3_free(error);
    sqlite3_close(connection);
    if (result != SQLITE_OK) {
        throw std::runtime_error("cannot drop the indexes of " + db.string() + ": " + message);
    }
}

}  // namespace stage1
