#include <gtest/gtest.h>

#include <algorithm>
#include <fstream>
#include <sstream>

#include "stage1/range_based_datalake.hpp"
#include "support/temp_dir.hpp"

using stage1::range_folder_name;
using stage1::RangeBasedDatalake;
using stage1::testing::TempDir;

namespace {

std::string read_file(const std::filesystem::path& path) {
    std::ifstream file(path, std::ios::binary);
    std::ostringstream contents;
    contents << file.rdbuf();
    return contents.str();
}

}  // namespace

TEST(RangeFolderName, MatchesTheExampleFromTheSpec) {
    // shared/SPEC.md section 3's own example.
    EXPECT_EQ(range_folder_name(1342), "01000-01999");
}

TEST(RangeFolderName, HandlesTheFirstRange) {
    EXPECT_EQ(range_folder_name(0), "00000-00999");
    EXPECT_EQ(range_folder_name(999), "00000-00999");
}

TEST(RangeFolderName, ARangeStartIsItsOwnFirstMember) {
    EXPECT_EQ(range_folder_name(1000), "01000-01999");
}

TEST(RangeFolderName, HandlesLargeIds) {
    EXPECT_EQ(range_folder_name(70000), "70000-70999");
}

TEST(RangeBasedDatalake, WritesBodyAndHeaderAtTheExpectedPaths) {
    TempDir root("stage1_range_based_datalake_test");
    RangeBasedDatalake datalake(root.path());

    auto location = datalake.write(1342, "Title: Pride and Prejudice", "It is a truth universally acknowledged...");

    EXPECT_EQ(location.body_path, (root.path() / "01000-01999" / "1342.body.txt").string());
    EXPECT_EQ(location.header_path, (root.path() / "01000-01999" / "1342.header.txt").string());
    EXPECT_EQ(read_file(location.body_path), "It is a truth universally acknowledged...");
    EXPECT_EQ(read_file(location.header_path), "Title: Pride and Prejudice");
}

TEST(RangeBasedDatalake, BooksInTheSameRangeShareTheFolderWithoutColliding) {
    TempDir root("stage1_range_based_datalake_test_shared");
    RangeBasedDatalake datalake(root.path());

    datalake.write(1342, "header 1342", "body 1342");
    datalake.write(1500, "header 1500", "body 1500");

    EXPECT_EQ(read_file(root.path() / "01000-01999" / "1342.body.txt"), "body 1342");
    EXPECT_EQ(read_file(root.path() / "01000-01999" / "1500.body.txt"), "body 1500");
}

TEST(RangeBasedDatalake, BooksInDifferentRangesGetSeparateFolders) {
    TempDir root("stage1_range_based_datalake_test_separate");
    RangeBasedDatalake datalake(root.path());

    datalake.write(999, "header a", "body a");
    datalake.write(1000, "header b", "body b");

    EXPECT_TRUE(std::filesystem::exists(root.path() / "00000-00999" / "999.body.txt"));
    EXPECT_TRUE(std::filesystem::exists(root.path() / "01000-01999" / "1000.body.txt"));
}

TEST(RangeBasedDatalake, LocateFindsAWrittenBookEvenFromAFreshInstance) {
    TempDir root("stage1_range_based_datalake_test_locate");
    RangeBasedDatalake(root.path()).write(1342, "header", "body");

    // A brand new instance, same root: the path is computed from the id
    // alone, so it does not need to be the same object that wrote it.
    RangeBasedDatalake reopened(root.path());
    auto found = reopened.locate(1342);

    ASSERT_TRUE(found.has_value());
    EXPECT_EQ(found->body_path, (root.path() / "01000-01999" / "1342.body.txt").string());
}

TEST(RangeBasedDatalake, LocateReturnsNulloptForAnUnwrittenBook) {
    TempDir root("stage1_range_based_datalake_test_locate_missing");
    RangeBasedDatalake datalake(root.path());

    EXPECT_FALSE(datalake.locate(404).has_value());
}

TEST(RangeBasedDatalake, ListBookIdsFindsEveryWrittenBookAcrossRanges) {
    TempDir root("stage1_range_based_datalake_test_list");
    RangeBasedDatalake datalake(root.path());
    datalake.write(1342, "h", "b");
    datalake.write(999, "h", "b");

    auto ids = datalake.list_book_ids();
    std::sort(ids.begin(), ids.end());

    EXPECT_EQ(ids, (std::vector<int>{999, 1342}));
}
