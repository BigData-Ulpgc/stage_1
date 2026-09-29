#include <gtest/gtest.h>

#include <fstream>

#include "stage1/book_id_list.hpp"
#include "support/temp_dir.hpp"

using stage1::load_book_ids;
using stage1::testing::TempDir;

TEST(LoadBookIds, LoadsIdsInFileOrder) {
    TempDir root("stage1_book_id_list_test_order");
    const auto path = root.path() / "book_ids.txt";
    std::ofstream(path) << "1342\n84\n11\n";

    EXPECT_EQ(load_book_ids(path), (std::vector<int>{1342, 84, 11}));
}

TEST(LoadBookIds, IgnoresBlankAndCommentLines) {
    TempDir root("stage1_book_id_list_test_comments");
    const auto path = root.path() / "book_ids.txt";
    std::ofstream(path) << "# Dataset comment\n\n1342\n   \n# another comment\n84\n";

    EXPECT_EQ(load_book_ids(path), (std::vector<int>{1342, 84}));
}

TEST(LoadBookIds, ThrowsWhenFileDoesNotExist) {
    EXPECT_THROW(load_book_ids("/no/such/book_ids.txt"), std::runtime_error);
}

TEST(LoadBookIds, LoadsTheRealSharedFile) {
    auto ids = load_book_ids(std::filesystem::path(STAGE1_SHARED_DIR) / "book_ids.txt");

    ASSERT_FALSE(ids.empty());
    EXPECT_EQ(ids.front(), 1342);  // Pride and Prejudice, the first entry today
}
