#include <gtest/gtest.h>

#include <fstream>
#include <nlohmann/json.hpp>
#include <sstream>

#include "fakes/fake_http_client.hpp"
#include "stage1/book_based_datalake.hpp"
#include "stage1/gutenberg_client.hpp"
#include "stage1/inverted_index.hpp"
#include "stage1/metadata_store.hpp"
#include "stage1/monolithic_index_writer.hpp"
#include "stage1/pipeline.hpp"
#include "support/temp_dir.hpp"

using stage1::ControlAction;
using stage1::ControlLog;
using stage1::DownloadResult;
using stage1::GutenbergSource;
using stage1::InvertedIndex;
using stage1::MetadataStore;
using stage1::MonolithicIndexWriter;
using stage1::run_pipeline_step;
using stage1::testing::FakeHttpClient;
using stage1::testing::TempDir;

namespace {

std::string read_file(const std::filesystem::path& path) {
    std::ifstream file(path, std::ios::binary);
    std::ostringstream contents;
    contents << file.rdbuf();
    return contents.str();
}

// A small but realistic Gutenberg-shaped text: has a header with fields for
// extract_metadata, the START/END markers split_book needs, and a body with
// a word repeated enough times to make stopword filtering visible.
const std::string kFakeBook =
    "Title: Fake Book\n"
    "Author: Jane Doe\n"
    "Language: English\n"
    "\n"
    "*** START OF THE PROJECT GUTENBERG EBOOK FAKE BOOK ***\n"
    "\n"
    "The whale swims. The whale dives.\n"
    "\n"
    "*** END OF THE PROJECT GUTENBERG EBOOK FAKE BOOK ***\n"
    "License text.\n";

// A minimal environment with everything run_pipeline_step needs, all backed
// by temporary directories/in-memory state so no test here touches the
// network or a real database.
struct PipelineFixture {
    TempDir root{"stage1_pipeline_test"};
    ControlLog downloaded{root.path() / "control" / "downloaded_books.txt"};
    ControlLog indexed{root.path() / "control" / "indexed_books.txt"};
    FakeHttpClient http_client{DownloadResult::success(kFakeBook)};
    GutenbergSource source{http_client};
    stage1::BookBasedDatalake datalake{root.path() / "datalake"};
    MetadataStore metadata{root.path() / "metadata.db"};
    InvertedIndex index;
    MonolithicIndexWriter index_writer{root.path() / "inverted_index.json"};
    const std::unordered_set<std::string> stopwords{"the"};

    stage1::ControlDecision step(const std::vector<int>& candidate_ids) {
        return run_pipeline_step(candidate_ids, downloaded, indexed, source, datalake, metadata, index, index_writer,
                                  stopwords);
    }
};

}  // namespace

TEST(Pipeline, FirstStepDownloadsTheFirstCandidate) {
    PipelineFixture fixture;

    auto decision = fixture.step({1342});

    EXPECT_EQ(decision.action, ControlAction::DownloadBook);
    EXPECT_EQ(decision.book_id, 1342);
    EXPECT_TRUE(fixture.downloaded.contains(1342));
    EXPECT_FALSE(fixture.indexed.contains(1342));

    auto stored = fixture.metadata.find_by_id(1342);
    ASSERT_TRUE(stored.has_value());
    EXPECT_EQ(stored->title, "Fake Book");
    EXPECT_EQ(read_file(stored->body_path), "The whale swims. The whale dives.");
}

TEST(Pipeline, SecondStepIndexesTheDownloadedBook) {
    PipelineFixture fixture;
    fixture.step({1342});  // downloads it first

    auto decision = fixture.step({1342});

    EXPECT_EQ(decision.action, ControlAction::IndexBook);
    EXPECT_TRUE(fixture.indexed.contains(1342));
    EXPECT_EQ(fixture.index.postings("whale"), std::vector<int>{1342});
    EXPECT_TRUE(fixture.index.postings("the").empty());  // "the" is a stopword

    auto on_disk = nlohmann::json::parse(read_file(fixture.root.path() / "inverted_index.json"));
    EXPECT_EQ(on_disk["whale"], nlohmann::json({1342}));
}

TEST(Pipeline, ThirdStepHasNothingLeftToDo) {
    PipelineFixture fixture;
    fixture.step({1342});  // download
    fixture.step({1342});  // index

    auto decision = fixture.step({1342});

    EXPECT_EQ(decision.action, ControlAction::Nothing);
}

TEST(Pipeline, AFailedDownloadIsNeverMarkedDownloaded) {
    PipelineFixture fixture;
    fixture.http_client = FakeHttpClient(DownloadResult::failure("HTTP 404"));

    auto decision = fixture.step({1342});

    EXPECT_EQ(decision.action, ControlAction::DownloadBook);  // attempted
    EXPECT_FALSE(fixture.downloaded.contains(1342));          // but never marked
}

TEST(Pipeline, ABookWithoutMarkersIsNeverMarkedDownloaded) {
    PipelineFixture fixture;
    fixture.http_client = FakeHttpClient(DownloadResult::success("Just some text, no Gutenberg markers at all."));

    auto decision = fixture.step({1342});

    EXPECT_EQ(decision.action, ControlAction::DownloadBook);
    EXPECT_FALSE(fixture.downloaded.contains(1342));
}
