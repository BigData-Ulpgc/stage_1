#include <gtest/gtest.h>

#include <algorithm>
#include <fstream>
#include <sstream>

#include "stage1/book_based_datalake.hpp"
#include "support/temp_dir.hpp"

using stage1::BookBasedDatalake;
using stage1::testing::TempDir;

namespace {

std::string read_file(const std::filesystem::path& path) {
    std::ifstream file(path, std::ios::binary);
    std::ostringstream contents;
    contents << file.rdbuf();
    return contents.str();
}

}  // namespace

TEST(BookBasedDatalake, WritesBodyAndHeaderAtTheExpectedPaths) {
    TempDir root("stage1_book_based_datalake_test");
    BookBasedDatalake datalake(root.path());

    auto location = datalake.write(1342, "Title: Pride and Prejudice", "It is a truth universally acknowledged...");

    EXPECT_EQ(location.body_path, (root.path() / "1342" / "body.txt").string());
    EXPECT_EQ(location.header_path, (root.path() / "1342" / "header.txt").string());
    EXPECT_EQ(read_file(location.body_path), "It is a truth universally acknowledged...");
    EXPECT_EQ(read_file(location.header_path), "Title: Pride and Prejudice");
}

TEST(BookBasedDatalake, DifferentBookIdsGetSeparateDirectories) {
    TempDir root("stage1_book_based_datalake_test_ids");
    BookBasedDatalake datalake(root.path());

    datalake.write(1, "header 1", "body 1");
    datalake.write(2, "header 2", "body 2");

    EXPECT_EQ(read_file(root.path() / "1" / "body.txt"), "body 1");
    EXPECT_EQ(read_file(root.path() / "2" / "body.txt"), "body 2");
}

TEST(BookBasedDatalake, WritingTheSameBookIdAgainReplacesTheContent) {
    TempDir root("stage1_book_based_datalake_test_overwrite");
    BookBasedDatalake datalake(root.path());

    datalake.write(1, "old header", "old body, much longer than the new one");
    datalake.write(1, "new header", "new body");

    EXPECT_EQ(read_file(root.path() / "1" / "body.txt"), "new body");
    EXPECT_EQ(read_file(root.path() / "1" / "header.txt"), "new header");
}

TEST(BookBasedDatalake, LocateFindsAWrittenBook) {
    TempDir root("stage1_book_based_datalake_test_locate");
    BookBasedDatalake datalake(root.path());
    auto location = datalake.write(1342, "header", "body");

    auto found = datalake.locate(1342);

    ASSERT_TRUE(found.has_value());
    EXPECT_EQ(found->body_path, location.body_path);
    EXPECT_EQ(found->header_path, location.header_path);
}

TEST(BookBasedDatalake, LocateReturnsNulloptForAnUnwrittenBook) {
    TempDir root("stage1_book_based_datalake_test_locate_missing");
    BookBasedDatalake datalake(root.path());

    EXPECT_FALSE(datalake.locate(404).has_value());
}

TEST(BookBasedDatalake, ListBookIdsFindsEveryWrittenBook) {
    TempDir root("stage1_book_based_datalake_test_list");
    BookBasedDatalake datalake(root.path());
    datalake.write(1342, "h", "b");
    datalake.write(84, "h", "b");

    auto ids = datalake.list_book_ids();
    std::sort(ids.begin(), ids.end());

    EXPECT_EQ(ids, (std::vector<int>{84, 1342}));
}

TEST(BookBasedDatalake, ListBookIdsIsEmptyForAFreshRoot) {
    TempDir root("stage1_book_based_datalake_test_list_empty");
    BookBasedDatalake datalake(root.path());

    EXPECT_TRUE(datalake.list_book_ids().empty());
}
