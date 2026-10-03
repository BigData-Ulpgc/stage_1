#include <gtest/gtest.h>

#include <sqlite3.h>

#include <filesystem>
#include <stdexcept>
#include <vector>

#include "stage1/datamart/metadata/metadata_store.hpp"

using stage1::BookMetadata;
using stage1::MetadataStore;
using stage1::StoredBook;

namespace {

// A temporary SQLite file path that is removed when the test ends. A real file
// (not ":memory:") is needed to check that the schema survives closing and
// reopening the database.
class TempDbPath {
public:
    TempDbPath() : path_(std::filesystem::temp_directory_path() / "stage1_metadata_store_test.db") {
        std::filesystem::remove(path_);  // leftovers from a crashed previous run
    }
    ~TempDbPath() { std::filesystem::remove(path_); }
    const std::filesystem::path& path() const { return path_; }

private:
    std::filesystem::path path_;
};

}  // namespace

TEST(MetadataStore, InsertThenFindReturnsTheSameData) {
    MetadataStore store(":memory:");
    BookMetadata metadata{"Robinson Crusoe", "Daniel Defoe", "1719", "English"};

    store.insert_book(5, metadata, "datalake/5/body.txt", "datalake/5/header.txt");
    auto book = store.find_by_id(5);

    ASSERT_TRUE(book.has_value());
    EXPECT_EQ(book->book_id, 5);
    EXPECT_EQ(book->title, "Robinson Crusoe");
    EXPECT_EQ(book->author, "Daniel Defoe");
    EXPECT_EQ(book->language, "English");
    EXPECT_EQ(book->release_date, "1719");
    EXPECT_EQ(book->body_path, "datalake/5/body.txt");
    EXPECT_EQ(book->header_path, "datalake/5/header.txt");
}

TEST(MetadataStore, FindMissingBookReturnsNullopt) {
    MetadataStore store(":memory:");
    EXPECT_FALSE(store.find_by_id(404).has_value());
}

TEST(MetadataStore, MissingMetadataFieldsRoundTripAsNullopt) {
    MetadataStore store(":memory:");
    BookMetadata metadata{"Untitled", std::nullopt, std::nullopt, std::nullopt};

    store.insert_book(1, metadata, "body.txt", "header.txt");
    auto book = store.find_by_id(1);

    ASSERT_TRUE(book.has_value());
    EXPECT_EQ(book->title, "Untitled");
    EXPECT_FALSE(book->author.has_value());
    EXPECT_FALSE(book->release_date.has_value());
    EXPECT_FALSE(book->language.has_value());
}

TEST(MetadataStore, InsertingTheSameIdTwiceReplacesTheRow) {
    MetadataStore store(":memory:");
    store.insert_book(1, BookMetadata{"First Title", std::nullopt, std::nullopt, std::nullopt}, "b", "h");
    store.insert_book(1, BookMetadata{"Second Title", std::nullopt, std::nullopt, std::nullopt}, "b", "h");

    auto book = store.find_by_id(1);
    ASSERT_TRUE(book.has_value());
    EXPECT_EQ(book->title, "Second Title");
}

TEST(MetadataStore, ValuesWithApostrophesRoundTripCorrectly) {
    // A naive "build the SQL string with +" approach would break (or worse, be
    // exploitable) on a title like this; parameter binding must handle it safely.
    MetadataStore store(":memory:");
    BookMetadata metadata{"Bob's Book: It's a Trap!", "O'Brien", std::nullopt, std::nullopt};

    store.insert_book(1, metadata, "b", "h");
    auto book = store.find_by_id(1);

    ASSERT_TRUE(book.has_value());
    EXPECT_EQ(book->title, "Bob's Book: It's a Trap!");
    EXPECT_EQ(book->author, "O'Brien");
}

TEST(MetadataStore, ReopeningTheSameDatabaseFileKeepsTheSchemaAndData) {
    TempDbPath db_path;
    {
        MetadataStore store(db_path.path());
        store.insert_book(1, BookMetadata{"Title", std::nullopt, std::nullopt, std::nullopt}, "b", "h");
    }  // store destroyed here: the connection is closed

    MetadataStore reopened(db_path.path());  // must not throw: CREATE TABLE IF NOT EXISTS
    auto book = reopened.find_by_id(1);
    ASSERT_TRUE(book.has_value());
    EXPECT_EQ(book->title, "Title");
}

TEST(MetadataStore, CreatesMissingParentDirectories) {
    TempDbPath db_path;
    const auto nested_path = db_path.path().parent_path() / "datamarts" / "metadata.db";

    MetadataStore store(nested_path);  // must not throw
    store.insert_book(1, BookMetadata{"Title", std::nullopt, std::nullopt, std::nullopt}, "b", "h");

    EXPECT_TRUE(std::filesystem::exists(nested_path));
    std::filesystem::remove_all(db_path.path().parent_path() / "datamarts");
}

TEST(MetadataStore, FindByAuthorReturnsEveryMatchingBook) {
    MetadataStore store(":memory:");
    store.insert_book(1, BookMetadata{"Emma", "Jane Austen", std::nullopt, std::nullopt}, "b1", "h1");
    store.insert_book(2, BookMetadata{"Persuasion", "Jane Austen", std::nullopt, std::nullopt}, "b2", "h2");
    store.insert_book(3, BookMetadata{"Frankenstein", "Mary Shelley", std::nullopt, std::nullopt}, "b3", "h3");

    auto books = store.find_by_author("Jane Austen");

    ASSERT_EQ(books.size(), 2u);
    EXPECT_NE(books[0].book_id, books[1].book_id);
    for (const auto& book : books) {
        EXPECT_EQ(book.author, "Jane Austen");
    }
}

TEST(MetadataStore, FindByAuthorWithNoMatchesReturnsEmpty) {
    MetadataStore store(":memory:");
    store.insert_book(1, BookMetadata{"Emma", "Jane Austen", std::nullopt, std::nullopt}, "b", "h");

    EXPECT_TRUE(store.find_by_author("Unknown Author").empty());
}

TEST(MetadataStore, FindByTitleReturnsEveryMatchingBook) {
    MetadataStore store(":memory:");
    store.insert_book(1, BookMetadata{"Same Title", "Author A", std::nullopt, std::nullopt}, "b1", "h1");
    store.insert_book(2, BookMetadata{"Same Title", "Author B", std::nullopt, std::nullopt}, "b2", "h2");

    auto books = store.find_by_title("Same Title");

    EXPECT_EQ(books.size(), 2u);
}

TEST(MetadataStore, FindByAuthorIsAnExactMatchNotASubstringSearch) {
    MetadataStore store(":memory:");
    store.insert_book(1, BookMetadata{"T", "Jane Austen", std::nullopt, std::nullopt}, "b", "h");

    EXPECT_TRUE(store.find_by_author("Jane").empty());  // substring, not exact: no match
}

namespace {

StoredBook row(int book_id, const std::string& title, const std::string& author) {
    return StoredBook{book_id, title, author, "English", "2000", "b" + std::to_string(book_id),
                      "h" + std::to_string(book_id)};
}

}  // namespace

TEST(MetadataStore, InsertBooksStoresTheWholeBatch) {
    MetadataStore store(":memory:");

    store.insert_books({row(1, "One", "A"), row(2, "Two", "B"), row(3, "Three", "C")});

    EXPECT_EQ(store.count(), 3);
    const auto book = store.find_by_id(2);
    ASSERT_TRUE(book.has_value());
    EXPECT_EQ(book->title, "Two");
    EXPECT_EQ(book->language, "English");
    EXPECT_EQ(book->body_path, "b2");
}

TEST(MetadataStore, InsertBooksWithAnEmptyBatchDoesNothing) {
    MetadataStore store(":memory:");

    store.insert_books({});

    EXPECT_EQ(store.count(), 0);
}

TEST(MetadataStore, InsertBooksIsAllOrNothing) {
    TempDbPath db_path;
    MetadataStore store(db_path.path());
    {
        // A trigger that makes book 3 fail, added through a connection of the test's own.
        sqlite3* connection = nullptr;
        ASSERT_EQ(sqlite3_open(db_path.path().string().c_str(), &connection), SQLITE_OK);
        ASSERT_EQ(sqlite3_exec(connection,
                               "CREATE TRIGGER fail_on_3 BEFORE INSERT ON books WHEN NEW.book_id = 3 "
                               "BEGIN SELECT RAISE(ABORT, 'book 3 refused'); END;",
                               nullptr, nullptr, nullptr),
                  SQLITE_OK);
        sqlite3_close(connection);
    }

    EXPECT_THROW(store.insert_books({row(1, "One", "A"), row(2, "Two", "B"), row(3, "Three", "C")}),
                 std::runtime_error);

    EXPECT_EQ(store.count(), 0);  // books 1 and 2 were rolled back with the failing one
    store.insert_book(4, BookMetadata{"Four", std::nullopt, std::nullopt, std::nullopt}, "b", "h");
    EXPECT_EQ(store.count(), 1);  // and the store is usable again: no transaction left open
}

TEST(MetadataStore, FindByAuthorAndTitleReturnBooksInAscendingIdOrder) {
    MetadataStore store(":memory:");
    store.insert_books({row(30, "Same", "Same"), row(10, "Same", "Same"), row(20, "Same", "Same")});

    const auto by_author = store.find_by_author("Same");
    const auto by_title = store.find_by_title("Same");

    ASSERT_EQ(by_author.size(), 3u);
    ASSERT_EQ(by_title.size(), 3u);
    for (std::size_t i = 0; i < 3; ++i) {
        EXPECT_EQ(by_author[i].book_id, static_cast<int>(10 * (i + 1)));
        EXPECT_EQ(by_title[i].book_id, static_cast<int>(10 * (i + 1)));
    }
}
