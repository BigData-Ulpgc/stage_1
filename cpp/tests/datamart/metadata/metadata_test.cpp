#include <gtest/gtest.h>

#include "stage1/datamart/metadata/metadata.hpp"

using stage1::extract_metadata;

TEST(Metadata, ExtractsAllFourFields) {
    const auto metadata = extract_metadata(
        "Title: Robinson Crusoe\n"
        "Author: Daniel Defoe\n"
        "Release date: June 25, 2008 [eBook #521]\n"
        "Language: English\n"
        "Credits: Produced by ...");

    ASSERT_TRUE(metadata.title.has_value());
    EXPECT_EQ(*metadata.title, "Robinson Crusoe");
    ASSERT_TRUE(metadata.author.has_value());
    EXPECT_EQ(*metadata.author, "Daniel Defoe");
    ASSERT_TRUE(metadata.language.has_value());
    EXPECT_EQ(*metadata.language, "English");
}

TEST(Metadata, StripsTheBracketedNoteFromTheReleaseDate) {
    const auto metadata = extract_metadata("Release date: June 25, 2008 [eBook #521]");
    ASSERT_TRUE(metadata.release_date.has_value());
    EXPECT_EQ(*metadata.release_date, "June 25, 2008");
}

TEST(Metadata, KeepsTheWholeReleaseDateWhenThereIsNoBracketedNote) {
    const auto metadata = extract_metadata("Release date: June 25, 2008");
    ASSERT_TRUE(metadata.release_date.has_value());
    EXPECT_EQ(*metadata.release_date, "June 25, 2008");
}

TEST(Metadata, MissingFieldIsNullopt) {
    const auto metadata = extract_metadata("Title: Robinson Crusoe\nLanguage: English");
    EXPECT_FALSE(metadata.author.has_value());
    EXPECT_FALSE(metadata.release_date.has_value());
}

TEST(Metadata, EmptyHeaderGivesAllNullopt) {
    const auto metadata = extract_metadata("");
    EXPECT_FALSE(metadata.title.has_value());
    EXPECT_FALSE(metadata.author.has_value());
    EXPECT_FALSE(metadata.release_date.has_value());
    EXPECT_FALSE(metadata.language.has_value());
}

TEST(Metadata, FirstOccurrenceWinsWhenAFieldAppearsTwice) {
    const auto metadata = extract_metadata("Title: First\nTitle: Second");
    ASSERT_TRUE(metadata.title.has_value());
    EXPECT_EQ(*metadata.title, "First");
}

TEST(Metadata, TrimsWhitespaceAroundTheValue) {
    const auto metadata = extract_metadata("Title:    Spaced Title   \n");
    ASSERT_TRUE(metadata.title.has_value());
    EXPECT_EQ(*metadata.title, "Spaced Title");
}
