#include <gtest/gtest.h>

#include <fstream>
#include <sstream>

#include "stage1/hierarchical_index_writer.hpp"
#include "stage1/inverted_index.hpp"
#include "support/temp_dir.hpp"

using stage1::hierarchical_folder_name;
using stage1::HierarchicalIndexWriter;
using stage1::InvertedIndex;
using stage1::testing::TempDir;

namespace {

std::string read_file(const std::filesystem::path& path) {
    std::ifstream file(path, std::ios::binary);
    std::ostringstream contents;
    contents << file.rdbuf();
    return contents.str();
}

}  // namespace

TEST(HierarchicalFolderName, UppercasesTheFirstLetter) {
    EXPECT_EQ(hierarchical_folder_name("car"), "C");
    EXPECT_EQ(hierarchical_folder_name("boat"), "B");
}

TEST(HierarchicalFolderName, ADigitLeadingTermUppercasesToItself) {
    // tokenize() lets tokens start with a digit (e.g. a year, "1876").
    EXPECT_EQ(hierarchical_folder_name("1876"), "1");
}

TEST(HierarchicalIndexWriter, WritesOnePostingPerLine) {
    TempDir root("stage1_hierarchical_index_writer_test");
    InvertedIndex index;
    index.add_book(3, {"car"});
    index.add_book(1, {"car"});

    HierarchicalIndexWriter(root.path()).write(index);

    EXPECT_EQ(read_file(root.path() / "C" / "car.txt"), "1\n3\n");
}

TEST(HierarchicalIndexWriter, TermsSharingAFirstLetterGoIntoTheSameFolder) {
    TempDir root("stage1_hierarchical_index_writer_test_shared");
    InvertedIndex index;
    index.add_book(1, {"car", "cat"});

    HierarchicalIndexWriter(root.path()).write(index);

    EXPECT_EQ(read_file(root.path() / "C" / "car.txt"), "1\n");
    EXPECT_EQ(read_file(root.path() / "C" / "cat.txt"), "1\n");
}

TEST(HierarchicalIndexWriter, DifferentFirstLettersGetSeparateFolders) {
    TempDir root("stage1_hierarchical_index_writer_test_letters");
    InvertedIndex index;
    index.add_book(1, {"car", "boat"});

    HierarchicalIndexWriter(root.path()).write(index);

    EXPECT_TRUE(std::filesystem::exists(root.path() / "C" / "car.txt"));
    EXPECT_TRUE(std::filesystem::exists(root.path() / "B" / "boat.txt"));
}

TEST(HierarchicalIndexWriter, EmptyIndexWritesNoFiles) {
    TempDir root("stage1_hierarchical_index_writer_test_empty");
    EXPECT_NO_THROW(HierarchicalIndexWriter(root.path()).write(InvertedIndex{}));
}

TEST(HierarchicalIndexWriter, UpdateTermsOnlyTouchesTheGivenTermsFiles) {
    TempDir root("stage1_hierarchical_index_writer_test_update");
    InvertedIndex index;
    index.add_book(1, {"car", "boat"});
    HierarchicalIndexWriter writer(root.path());
    writer.write(index);

    // A new book adds "car" (now in two books) and a brand-new term "dragon".
    index.add_book(2, {"car", "dragon"});
    writer.update_terms(index, {"car", "dragon"});

    EXPECT_EQ(read_file(root.path() / "C" / "car.txt"), "1\n2\n");
    EXPECT_EQ(read_file(root.path() / "D" / "dragon.txt"), "2\n");
    // "boat" was never in changed_terms: its file must be exactly as write() left it.
    EXPECT_EQ(read_file(root.path() / "B" / "boat.txt"), "1\n");
}

TEST(HierarchicalIndexWriter, UpdateTermsWithAnEmptyListTouchesNothing) {
    TempDir root("stage1_hierarchical_index_writer_test_update_empty");
    InvertedIndex index;
    index.add_book(1, {"car"});
    HierarchicalIndexWriter writer(root.path());
    writer.write(index);

    EXPECT_NO_THROW(writer.update_terms(index, {}));
    EXPECT_EQ(read_file(root.path() / "C" / "car.txt"), "1\n");
}
