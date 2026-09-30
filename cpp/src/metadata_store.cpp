#include "stage1/metadata_store.hpp"

#include <sqlite3.h>

#include <stdexcept>

namespace stage1 {

namespace {

// Owns a sqlite3_stmt*: prepares it in the constructor, always finalizes it in the
// destructor, on every path out of the function that uses it (normal return or an
// exception unwinding through it).
class Statement {
public:
    Statement(sqlite3* db, const char* sql) {
        if (sqlite3_prepare_v2(db, sql, -1, &stmt_, nullptr) != SQLITE_OK) {
            throw std::runtime_error(std::string("failed to prepare statement: ") + sqlite3_errmsg(db));
        }
    }
    ~Statement() { sqlite3_finalize(stmt_); }

    Statement(const Statement&) = delete;
    Statement& operator=(const Statement&) = delete;

    sqlite3_stmt* get() const { return stmt_; }

private:
    sqlite3_stmt* stmt_;
};

// Binds `value` at the 1-based parameter `index`: its text if present, SQL NULL
// otherwise. SQLITE_TRANSIENT tells SQLite to copy the string internally, since
// `value` may be destroyed before the statement runs.
void bind_optional_text(sqlite3_stmt* stmt, int index, const std::optional<std::string>& value) {
    if (value) {
        sqlite3_bind_text(stmt, index, value->c_str(), -1, SQLITE_TRANSIENT);
    } else {
        sqlite3_bind_null(stmt, index);
    }
}

std::optional<std::string> column_optional_text(sqlite3_stmt* stmt, int index) {
    if (sqlite3_column_type(stmt, index) == SQLITE_NULL) {
        return std::nullopt;
    }
    const auto* text = reinterpret_cast<const char*>(sqlite3_column_text(stmt, index));
    return std::string(text);
}

std::string column_text(sqlite3_stmt* stmt, int index) {
    const auto* text = reinterpret_cast<const char*>(sqlite3_column_text(stmt, index));
    return text ? std::string(text) : std::string();
}

void exec(sqlite3* db, const char* sql) {
    char* error = nullptr;
    if (sqlite3_exec(db, sql, nullptr, nullptr, &error) != SQLITE_OK) {
        const std::string message = error ? error : "unknown SQLite error";
        sqlite3_free(error);
        throw std::runtime_error("failed to execute statement: " + message);
    }
}

constexpr const char* kCreateTableSql =
    "CREATE TABLE IF NOT EXISTS books ("
    "  book_id      INTEGER PRIMARY KEY,"
    "  title        TEXT,"
    "  author       TEXT,"
    "  language     TEXT,"
    "  release_date TEXT,"
    "  body_path    TEXT,"
    "  header_path  TEXT"
    ");";
constexpr const char* kCreateAuthorIndexSql = "CREATE INDEX IF NOT EXISTS idx_books_author ON books(author);";
constexpr const char* kCreateTitleIndexSql = "CREATE INDEX IF NOT EXISTS idx_books_title ON books(title);";

}  // namespace

MetadataStore::MetadataStore(const std::filesystem::path& db_path) {
    // Skipped for ":memory:" and similar special SQLite names, which have no
    // real parent directory: their parent_path() is empty, so this is a no-op.
    const std::filesystem::path parent = db_path.parent_path();
    if (!parent.empty()) {
        std::error_code error;
        std::filesystem::create_directories(parent, error);
        if (error) {
            throw std::runtime_error("cannot create directory " + parent.string() + ": " + error.message());
        }
    }

    if (sqlite3_open(db_path.string().c_str(), &db_) != SQLITE_OK) {
        const std::string message = sqlite3_errmsg(db_);
        sqlite3_close(db_);
        throw std::runtime_error("cannot open metadata database: " + message);
    }
    exec(db_, kCreateTableSql);
    exec(db_, kCreateAuthorIndexSql);
    exec(db_, kCreateTitleIndexSql);
}

MetadataStore::~MetadataStore() { sqlite3_close(db_); }

void MetadataStore::insert_book(int book_id, const BookMetadata& metadata, const std::string& body_path,
                                 const std::string& header_path) {
    static constexpr const char* kSql =
        "INSERT OR REPLACE INTO books (book_id, title, author, language, release_date, body_path, header_path) "
        "VALUES (?, ?, ?, ?, ?, ?, ?);";
    Statement statement(db_, kSql);

    sqlite3_bind_int(statement.get(), 1, book_id);
    bind_optional_text(statement.get(), 2, metadata.title);
    bind_optional_text(statement.get(), 3, metadata.author);
    bind_optional_text(statement.get(), 4, metadata.language);
    bind_optional_text(statement.get(), 5, metadata.release_date);
    sqlite3_bind_text(statement.get(), 6, body_path.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(statement.get(), 7, header_path.c_str(), -1, SQLITE_TRANSIENT);

    if (sqlite3_step(statement.get()) != SQLITE_DONE) {
        throw std::runtime_error(std::string("failed to insert book: ") + sqlite3_errmsg(db_));
    }
}

std::optional<StoredBook> MetadataStore::find_by_id(int book_id) const {
    static constexpr const char* kSql =
        "SELECT book_id, title, author, language, release_date, body_path, header_path "
        "FROM books WHERE book_id = ?;";
    Statement statement(db_, kSql);
    sqlite3_bind_int(statement.get(), 1, book_id);

    const int step_result = sqlite3_step(statement.get());
    if (step_result == SQLITE_DONE) {
        return std::nullopt;  // no row with this id
    }
    if (step_result != SQLITE_ROW) {
        throw std::runtime_error(std::string("failed to query book: ") + sqlite3_errmsg(db_));
    }

    StoredBook book;
    book.book_id = sqlite3_column_int(statement.get(), 0);
    book.title = column_optional_text(statement.get(), 1);
    book.author = column_optional_text(statement.get(), 2);
    book.language = column_optional_text(statement.get(), 3);
    book.release_date = column_optional_text(statement.get(), 4);
    book.body_path = column_text(statement.get(), 5);
    book.header_path = column_text(statement.get(), 6);
    return book;
}

}  // namespace stage1
