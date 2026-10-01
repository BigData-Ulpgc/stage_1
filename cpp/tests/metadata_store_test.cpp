#include <gtest/gtest.h>

#include <filesystem>

#include "stage1/metadata_store.hpp"

using stage1::BookMetadata;
using stage1::MetadataStore;

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

TEST(MetadataStore, InsertsInsideATransactionAreVisibleAfterCommit) {
    MetadataStore store(":memory:");

    store.begin_transaction();
    store.insert_book(1, BookMetadata{"One", std::nullopt, std::nullopt, std::nullopt}, "b1", "h1");
    store.insert_book(2, BookMetadata{"Two", std::nullopt, std::nullopt, std::nullopt}, "b2", "h2");
    store.commit_transaction();

    EXPECT_TRUE(store.find_by_id(1).has_value());
    EXPECT_TRUE(store.find_by_id(2).has_value());
}

TEST(MetadataStore, RollbackDiscardsEverythingSinceBeginTransaction) {
    MetadataStore store(":memory:");
    store.insert_book(1, BookMetadata{"Already committed", std::nullopt, std::nullopt, std::nullopt}, "b", "h");

    store.begin_transaction();
    store.insert_book(2, BookMetadata{"Should vanish", std::nullopt, std::nullopt, std::nullopt}, "b", "h");
    store.rollback_transaction();

    EXPECT_TRUE(store.find_by_id(1).has_value());   // committed before the transaction: unaffected
    EXPECT_FALSE(store.find_by_id(2).has_value());  // rolled back: never really there
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
