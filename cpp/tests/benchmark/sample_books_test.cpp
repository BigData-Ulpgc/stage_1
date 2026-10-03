#include <gtest/gtest.h>

#include <fstream>

#include "stage1/datamart/metadata/metadata.hpp"
#include "stage1/benchmark/sample_books.hpp"
#include "support/temp_dir.hpp"

using stage1::BookMetadata;
using stage1::ControlLog;
using stage1::load_sample_books;
using stage1::MetadataStore;
using stage1::testing::TempDir;

TEST(LoadSampleBooks, LoadsOnlyDownloadedBooksWithTheirRealBodyFromDisk) {
    TempDir root("stage1_sample_books_test");
    ControlLog downloaded(root.path() / "downloaded_books.txt");
    MetadataStore metadata(root.path() / "metadata.db");

    const auto body_path = root.path() / "1342_body.txt";
    std::ofstream(body_path) << "It is a truth universally acknowledged.";
    const auto header_path = root.path() / "1342_header.txt";
    std::ofstream(header_path) << "Title: Pride and Prejudice";
    metadata.insert_book(1342, BookMetadata{"Pride and Prejudice", std::nullopt, std::nullopt, std::nullopt},
                          body_path.string(), header_path.string());
    downloaded.mark(1342);
    // 999 is a candidate but was never downloaded, so it must be skipped.

    auto books = load_sample_books({1342, 999}, downloaded, metadata);

    ASSERT_EQ(books.size(), 1u);
    EXPECT_EQ(books[0].book_id, 1342);
    EXPECT_EQ(books[0].body, "It is a truth universally acknowledged.");
    EXPECT_EQ(books[0].header, "Title: Pride and Prejudice");
}

TEST(LoadSampleBooks, ReturnsEmptyWhenNothingIsDownloaded) {
    TempDir root("stage1_sample_books_test_empty");
    ControlLog downloaded(root.path() / "downloaded_books.txt");
    MetadataStore metadata(root.path() / "metadata.db");

    EXPECT_TRUE(load_sample_books({1, 2, 3}, downloaded, metadata).empty());
}

TEST(LoadSampleBooks, PreservesCandidateOrder) {
    TempDir root("stage1_sample_books_test_order");
    ControlLog downloaded(root.path() / "downloaded_books.txt");
    MetadataStore metadata(root.path() / "metadata.db");

    for (int id : {5, 1342, 84}) {
        const auto body_path = root.path() / (std::to_string(id) + "_body.txt");
        std::ofstream(body_path) << "body " << id;
        const auto header_path = root.path() / (std::to_string(id) + "_header.txt");
        std::ofstream(header_path) << "header " << id;
        metadata.insert_book(id, BookMetadata{"T", std::nullopt, std::nullopt, std::nullopt}, body_path.string(),
                              header_path.string());
        downloaded.mark(id);
    }

    auto books = load_sample_books({84, 5, 1342}, downloaded, metadata);

    ASSERT_EQ(books.size(), 3u);
    EXPECT_EQ(books[0].book_id, 84);
    EXPECT_EQ(books[1].book_id, 5);
    EXPECT_EQ(books[2].book_id, 1342);
}
