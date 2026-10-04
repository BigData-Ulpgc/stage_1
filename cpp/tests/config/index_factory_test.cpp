#include <gtest/gtest.h>

#include <stdexcept>
#include <vector>

#include "stage1/config/index_factory.hpp"
#include "stage1/datamart/index/inverted_index.hpp"
#include "support/temp_dir.hpp"

using stage1::create_index_writer;
using stage1::index_exists;
using stage1::InvertedIndex;
using stage1::open_index;
using stage1::testing::TempDir;

TEST(IndexFactory, EachFileStructureIsWrittenWhereSpecSection6SaysAndReadBack) {
    TempDir root("stage1_index_factory_test_files");
    InvertedIndex index;
    index.add_book(84, {"whale", "island"});
    index.add_book(1342, {"whale"});

    for (const std::string structure : {"monolithic", "hierarchical"}) {
        EXPECT_FALSE(index_exists(structure, root.path())) << structure;

        create_index_writer(structure, root.path())->write(index);

        EXPECT_TRUE(index_exists(structure, root.path())) << structure;
        EXPECT_EQ(open_index(structure, root.path())("whale"), (std::vector<int>{84, 1342})) << structure;
    }
    EXPECT_TRUE(std::filesystem::exists(root.path() / "datamarts" / "inverted_index.json"));
    EXPECT_TRUE(std::filesystem::exists(root.path() / "datamarts" / "inverted_index" / "W" / "whale.txt"));
}

TEST(IndexFactory, RejectsAnUnknownStructure) {
    TempDir root("stage1_index_factory_test_unknown");

    EXPECT_THROW(create_index_writer("memory", root.path()), std::invalid_argument);
    EXPECT_THROW(open_index("memory", root.path()), std::invalid_argument);
    EXPECT_THROW(index_exists("memory", root.path()), std::invalid_argument);
}

TEST(IndexFactory, StoredTermCountIsZeroBeforeWritingAndTheIndexsTermsAfter) {
    TempDir root("stage1_index_factory_test_count");
    InvertedIndex index;
    index.add_book(84, {"whale", "island", "1984"});

    for (const std::string structure : {"monolithic", "hierarchical"}) {
        EXPECT_EQ(stage1::stored_term_count(structure, root.path()), 0u) << structure;

        create_index_writer(structure, root.path())->write(index);

        EXPECT_EQ(stage1::stored_term_count(structure, root.path()), 3u) << structure;
    }
}
