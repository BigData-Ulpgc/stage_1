#include <gtest/gtest.h>

#include <sqlite3.h>

#include <set>
#include <stdexcept>
#include <string>

#include "stage1/benchmark/metadata_benchmark_support.hpp"
#include "support/temp_dir.hpp"

using stage1::fresh_metadata_store;
using stage1::synthetic_metadata;
using stage1::testing::TempDir;

namespace {

// The names of the indexes the database file `db` holds.
std::set<std::string> index_names(const std::filesystem::path& db) {
    sqlite3* connection = nullptr;
    sqlite3_open(db.string().c_str(), &connection);
    std::set<std::string> names;
    sqlite3_exec(
        connection, "SELECT name FROM sqlite_master WHERE type = 'index';",
        [](void* out, int, char** values, char**) {
            static_cast<std::set<std::string>*>(out)->insert(values[0]);
            return 0;
        },
        &names, nullptr);
    sqlite3_close(connection);
    return names;
}

}  // namespace

TEST(SyntheticMetadata, FollowsTheGeneratorOfSpecSection10_2) {
    const auto rows = synthetic_metadata(12);

    ASSERT_EQ(rows.size(), 12u);
    EXPECT_EQ(rows[0].book_id, 1);
    EXPECT_EQ(rows[0].title, "Title 0");
    EXPECT_EQ(rows[0].author, "Author 0");
    EXPECT_EQ(rows[0].language, "English");
    EXPECT_EQ(rows[0].release_date, "January 1, 2000");
    EXPECT_EQ(rows[0].body_path, "datalake/book/1/body.txt");
    EXPECT_EQ(rows[0].header_path, "datalake/book/1/header.txt");
    EXPECT_EQ(rows[9].title, "Title 4");  // i = 9: 9/2 = 4
    EXPECT_EQ(rows[9].author, "Author 0");
    EXPECT_EQ(rows[11].book_id, 12);
    EXPECT_EQ(rows[11].title, "Title 5");
    EXPECT_EQ(rows[11].author, "Author 1");  // i = 11: 11/10 = 1
}

TEST(SyntheticMetadata, ASmallerSizeIsAPrefixOfALargerOne) {
    const auto small = synthetic_metadata(100);
    const auto large = synthetic_metadata(1000);

    for (std::size_t i = 0; i < small.size(); ++i) {
        EXPECT_EQ(small[i].book_id, large[i].book_id);
        EXPECT_EQ(small[i].title, large[i].title);
        EXPECT_EQ(small[i].author, large[i].author);
    }
}

TEST(FreshMetadataStore, StartsEmptyWhateverTheFileHeldBefore) {
    TempDir root("stage1_fresh_metadata_store_test_empty");
    const auto db = root.path() / "sqlite_10.db";
    fresh_metadata_store("sqlite", db)->insert_books(synthetic_metadata(10));

    const auto store = fresh_metadata_store("sqlite", db);

    EXPECT_EQ(store->count(), 0);
}

TEST(FreshMetadataStore, OnlyTheNoIndexVariantLacksTheAuthorAndTitleIndexes) {
    TempDir root("stage1_fresh_metadata_store_test_indexes");
    const auto with_indexes = root.path() / "sqlite.db";
    const auto without_indexes = root.path() / "sqlite_no_index.db";

    fresh_metadata_store("sqlite", with_indexes);
    fresh_metadata_store("sqlite_no_index", without_indexes);

    EXPECT_EQ(index_names(with_indexes), (std::set<std::string>{"idx_books_author", "idx_books_title"}));
    EXPECT_TRUE(index_names(without_indexes).empty());
}

TEST(FreshMetadataStore, RejectsAnUnknownStructure) {
    TempDir root("stage1_fresh_metadata_store_test_unknown");
    EXPECT_THROW(fresh_metadata_store("postgres", root.path() / "x.db"), std::invalid_argument);
}
