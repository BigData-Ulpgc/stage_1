#include <gtest/gtest.h>

#include <fstream>

#include <nlohmann/json.hpp>

#include "stage1/datamart/index/inverted_index.hpp"
#include "stage1/datamart/index/monolithic_index_writer.hpp"
#include "support/temp_dir.hpp"

using stage1::InvertedIndex;
using stage1::MonolithicIndexWriter;
using stage1::testing::TempDir;

TEST(MonolithicIndexWriter, WritesTermsAsAJsonObjectOfSortedPostings) {
    TempDir root("stage1_monolithic_index_writer_test");
    const auto path = root.path() / "inverted_index.json";

    InvertedIndex index;
    index.add_book(3, {"car"});
    index.add_book(1, {"car", "nice"});

    MonolithicIndexWriter(path).write(index);

    std::ifstream file(path);
    const auto document = nlohmann::json::parse(file);
    EXPECT_EQ(document["car"], nlohmann::json({1, 3}));
    EXPECT_EQ(document["nice"], nlohmann::json({1}));
}

TEST(MonolithicIndexWriter, EmptyIndexWritesAnEmptyJsonObject) {
    TempDir root("stage1_monolithic_index_writer_test_empty");
    const auto path = root.path() / "inverted_index.json";

    MonolithicIndexWriter(path).write(InvertedIndex{});

    std::ifstream file(path);
    const auto document = nlohmann::json::parse(file);
    EXPECT_TRUE(document.is_object());
    EXPECT_TRUE(document.empty());
}

TEST(MonolithicIndexWriter, CreatesMissingParentDirectories) {
    TempDir root("stage1_monolithic_index_writer_test_dirs");
    const auto path = root.path() / "datamarts" / "inverted_index.json";

    InvertedIndex index;
    index.add_book(1, {"car"});
    MonolithicIndexWriter(path).write(index);

    EXPECT_TRUE(std::filesystem::exists(path));
}
