#include <gtest/gtest.h>

#include <filesystem>
#include <fstream>

#include "stage1/stopwords.hpp"

using stage1::load_stopwords;

namespace {

// Writes `content` to a temporary file and deletes it when the test ends.
class TempFile {
public:
    explicit TempFile(const std::string& content)
        : path_(std::filesystem::temp_directory_path() / "stage1_stopwords_test.txt") {
        std::ofstream out(path_, std::ios::binary);
        out << content;
    }
    ~TempFile() { std::filesystem::remove(path_); }
    const std::filesystem::path& path() const { return path_; }

private:
    std::filesystem::path path_;
};

}  // namespace

TEST(Stopwords, IgnoresCommentsBlankLinesAndWindowsLineEndings) {
    TempFile file("# a comment\nthe\r\n\n  and  \nof");
    auto stopwords = load_stopwords(file.path());
    EXPECT_EQ(stopwords.size(), 3u);
    EXPECT_TRUE(stopwords.contains("the"));
    EXPECT_TRUE(stopwords.contains("and"));
    EXPECT_TRUE(stopwords.contains("of"));
}

TEST(Stopwords, ThrowsWhenFileDoesNotExist) {
    EXPECT_THROW(load_stopwords("/no/such/stopwords.txt"), std::runtime_error);
}

TEST(Stopwords, LoadsTheSharedProjectFile) {
    auto stopwords = load_stopwords(std::filesystem::path(STAGE1_SHARED_DIR) / "stopwords.txt");
    EXPECT_TRUE(stopwords.contains("the"));
    EXPECT_FALSE(stopwords.contains("car"));
    EXPECT_FALSE(stopwords.contains("# Stopwords en inglés (lista corta; acordar en grupo si se amplía)"));
}
