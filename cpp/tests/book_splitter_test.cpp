#include <gtest/gtest.h>

#include "stage1/book_splitter.hpp"

using stage1::split_book;

namespace {

const std::string kBook =
    "Title: Test Book\n"
    "Author: Jane Doe\n"
    "\n"
    "*** START OF THE PROJECT GUTENBERG EBOOK TEST BOOK ***\n"
    "\n"
    "Hello world.\n"
    "Second line.\n"
    "\n"
    "*** END OF THE PROJECT GUTENBERG EBOOK TEST BOOK ***\n"
    "License text that must be discarded.\n";

}  // namespace

TEST(BookSplitter, SplitsHeaderAndBodyAndDiscardsFooter) {
    auto book = split_book(kBook);
    ASSERT_TRUE(book.has_value());
    EXPECT_EQ(book->header, "Title: Test Book\nAuthor: Jane Doe");
    // The rest of the START line ("TEST BOOK ***") is not part of the body.
    EXPECT_EQ(book->body, "Hello world.\nSecond line.");
}

TEST(BookSplitter, AcceptsTheThisVariantOfTheMarkers) {
    auto book = split_book(
        "Header\n*** START OF THIS PROJECT GUTENBERG EBOOK X ***\nBody\n*** END OF THIS PROJECT GUTENBERG EBOOK X ***\n");
    ASSERT_TRUE(book.has_value());
    EXPECT_EQ(book->header, "Header");
    EXPECT_EQ(book->body, "Body");
}

TEST(BookSplitter, NormalizesWindowsLineEndings) {
    auto book = split_book(
        "Title: T\r\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\r\nLine1\r\nLine2\r\n*** END OF THE PROJECT GUTENBERG EBOOK X ***\r\n");
    ASSERT_TRUE(book.has_value());
    EXPECT_EQ(book->header, "Title: T");
    EXPECT_EQ(book->body, "Line1\nLine2");
}

TEST(BookSplitter, ReturnsNothingWhenStartMarkerIsMissing) {
    EXPECT_FALSE(split_book("Body\n*** END OF THE PROJECT GUTENBERG EBOOK X ***\n").has_value());
}

TEST(BookSplitter, ReturnsNothingWhenEndMarkerIsMissing) {
    EXPECT_FALSE(split_book("H\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\nBody with no end\n").has_value());
}

TEST(BookSplitter, EndMarkerBeforeStartMarkerDoesNotCount) {
    EXPECT_FALSE(split_book("*** END OF THE PROJECT GUTENBERG EBOOK X\n"
                            "*** START OF THE PROJECT GUTENBERG EBOOK X ***\nBody\n")
                     .has_value());
}

TEST(BookSplitter, EmptyBodyIsAllowed) {
    auto book = split_book("H\n*** START OF THE PROJECT GUTENBERG EBOOK X ***\n*** END OF THE PROJECT GUTENBERG EBOOK X ***");
    ASSERT_TRUE(book.has_value());
    EXPECT_EQ(book->body, "");
}
