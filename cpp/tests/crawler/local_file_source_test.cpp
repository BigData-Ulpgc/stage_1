#include <gtest/gtest.h>

#include <filesystem>
#include <fstream>
#include <sstream>

#include "stage1/control/book_id_list.hpp"
#include "stage1/crawler/book_splitter.hpp"
#include "stage1/crawler/local_file_source.hpp"
#include "support/temp_dir.hpp"

using stage1::load_book_ids;
using stage1::LocalFileSource;
using stage1::split_book;
using stage1::testing::TempDir;

namespace {

std::string read_file(const std::filesystem::path& path) {
    std::ifstream file(path, std::ios::binary);
    std::ostringstream contents;
    contents << file.rdbuf();
    return contents.str();
}

}  // namespace

TEST(LocalFileSource, ReturnsTheBookFileByteForByte) {
    TempDir root("stage1_local_file_source_test");
    const std::string raw = "Title: X\r\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\r\ncaf\xc3\xa9\r\n";
    std::ofstream(root.path() / "pg84.txt", std::ios::binary) << raw;

    auto result = LocalFileSource(root.path()).fetch(84);

    ASSERT_TRUE(result.ok());
    EXPECT_EQ(result.text(), raw);  // "\r\n" and UTF-8 kept: normalizing is split_book's job
}

TEST(LocalFileSource, AMissingBookIsAFailedFetchNotAnException) {
    TempDir root("stage1_local_file_source_test_missing");

    auto result = LocalFileSource(root.path()).fetch(999);

    ASSERT_FALSE(result.ok());
    EXPECT_NE(result.error().find("pg999.txt"), std::string::npos);
}

TEST(LocalFileSource, SplittingTheRealSampleDatasetReproducesItsBookFolder) {
    // sample_dataset/raw/ is the input of SPEC section 2 and sample_dataset/book/
    // its expected output: reading through LocalFileSource and splitting must
    // give back book/ byte for byte, for every one of the 15 books.
    const std::filesystem::path sample = std::filesystem::path(STAGE1_SHARED_DIR) / ".." / "sample_dataset";
    LocalFileSource source(sample / "raw");

    const auto ids = load_book_ids(sample / "book_ids.txt");
    ASSERT_EQ(ids.size(), 15u);
    for (int id : ids) {
        auto result = source.fetch(id);
        ASSERT_TRUE(result.ok()) << id;
        const auto split = split_book(result.text());
        ASSERT_TRUE(split.has_value()) << id;
        const auto expected = sample / "book" / std::to_string(id);
        EXPECT_EQ(split->header, read_file(expected / "header.txt")) << id;
        EXPECT_EQ(split->body, read_file(expected / "body.txt")) << id;
    }
}
