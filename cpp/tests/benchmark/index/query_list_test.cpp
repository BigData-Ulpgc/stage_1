#include <gtest/gtest.h>

#include <fstream>

#include "stage1/benchmark/index/query_list.hpp"
#include "support/temp_dir.hpp"

using stage1::load_queries;
using stage1::testing::TempDir;

TEST(LoadQueries, LoadsQueriesInFileOrder) {
    TempDir root("stage1_query_list_test_order");
    const auto path = root.path() / "queries.txt";
    std::ofstream(path) << "adventure\nship sea\nking queen\n";

    EXPECT_EQ(load_queries(path), (std::vector<std::string>{"adventure", "ship sea", "king queen"}));
}

TEST(LoadQueries, IgnoresBlankAndCommentLines) {
    TempDir root("stage1_query_list_test_comments");
    const auto path = root.path() / "queries.txt";
    std::ofstream(path) << "# common query workload\n\nadventure\n   \nwhale\n";

    EXPECT_EQ(load_queries(path), (std::vector<std::string>{"adventure", "whale"}));
}

TEST(LoadQueries, ThrowsWhenFileDoesNotExist) {
    EXPECT_THROW(load_queries("/no/such/queries.txt"), std::runtime_error);
}

TEST(LoadQueries, LoadsTheRealSharedFile) {
    auto queries = load_queries(std::filesystem::path(STAGE1_SHARED_DIR) / "queries.txt");

    ASSERT_FALSE(queries.empty());
    EXPECT_EQ(queries.front(), "adventure");
}
