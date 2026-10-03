#include <gtest/gtest.h>

#include <fstream>
#include <sstream>

#include "stage1/control/control_log.hpp"
#include "support/temp_dir.hpp"

using stage1::ControlLog;
using stage1::testing::TempDir;

namespace {

std::string read_file(const std::filesystem::path& path) {
    std::ifstream file(path, std::ios::binary);
    std::ostringstream contents;
    contents << file.rdbuf();
    return contents.str();
}

}  // namespace

TEST(ControlLog, StartsEmptyWhenTheFileDoesNotExistYet) {
    TempDir root("stage1_control_log_test_empty");
    ControlLog log(root.path() / "downloaded_books.txt");

    EXPECT_FALSE(log.contains(1342));
    EXPECT_TRUE(log.ids().empty());
}

TEST(ControlLog, MarkRecordsAnIdOnDiskAndInMemory) {
    TempDir root("stage1_control_log_test_mark");
    const auto path = root.path() / "downloaded_books.txt";
    ControlLog log(path);

    log.mark(1342);

    EXPECT_TRUE(log.contains(1342));
    EXPECT_EQ(read_file(path), "1342\n");
}

TEST(ControlLog, ARecordedIdIsStillThereAfterReopeningTheLog) {
    TempDir root("stage1_control_log_test_reopen");
    const auto path = root.path() / "downloaded_books.txt";

    ControlLog(path).mark(1342);
    ControlLog reopened(path);  // simulates the pipeline restarting

    EXPECT_TRUE(reopened.contains(1342));
}

TEST(ControlLog, MarkingTheSameIdTwiceDoesNotDuplicateTheLine) {
    TempDir root("stage1_control_log_test_dup");
    const auto path = root.path() / "downloaded_books.txt";
    ControlLog log(path);

    log.mark(1342);
    log.mark(1342);  // e.g. the pipeline retried after a crash right after marking

    EXPECT_EQ(read_file(path), "1342\n");
}

TEST(ControlLog, MultipleIdsAreAllRecordedAndSurviveAReload) {
    TempDir root("stage1_control_log_test_multi");
    const auto path = root.path() / "downloaded_books.txt";

    {
        ControlLog log(path);
        log.mark(5);
        log.mark(1342);
        log.mark(999);
    }

    ControlLog reloaded(path);
    EXPECT_TRUE(reloaded.contains(5));
    EXPECT_TRUE(reloaded.contains(1342));
    EXPECT_TRUE(reloaded.contains(999));
    EXPECT_FALSE(reloaded.contains(6));
}

TEST(ControlLog, IdsListsEveryRecordedIdOnceInAscendingOrder) {
    TempDir root("stage1_control_log_test_ids");
    const auto path = root.path() / "downloaded_books.txt";
    ControlLog log(path);

    log.mark(1342);
    log.mark(5);
    log.mark(999);
    log.mark(5);  // a repeated mark must not show up twice

    EXPECT_EQ(log.ids(), (std::vector<int>{5, 999, 1342}));
    EXPECT_EQ(ControlLog(path).ids(), (std::vector<int>{5, 999, 1342}));  // same after a reload
}

TEST(ControlLog, IgnoresBlankLinesWhenLoadingAnExistingFile) {
    TempDir root("stage1_control_log_test_blanks");
    const auto path = root.path() / "downloaded_books.txt";
    std::ofstream(path) << "5\n\n1342\n   \n999\n";

    ControlLog log(path);

    EXPECT_TRUE(log.contains(5));
    EXPECT_TRUE(log.contains(1342));
    EXPECT_TRUE(log.contains(999));
}

TEST(ControlLog, CreatesMissingParentDirectories) {
    TempDir root("stage1_control_log_test_dirs");
    const auto path = root.path() / "control" / "downloaded_books.txt";

    ControlLog log(path);
    log.mark(1);

    EXPECT_TRUE(std::filesystem::exists(path));
}

using stage1::ControlAction;
using stage1::next_control_action;

TEST(NextControlAction, PicksTheFirstCandidateToDownloadWhenNothingIsDownloadedYet) {
    TempDir root("stage1_next_control_action_test_download");
    ControlLog downloaded(root.path() / "downloaded_books.txt");
    ControlLog indexed(root.path() / "indexed_books.txt");

    auto decision = next_control_action({5, 1342, 999}, downloaded, indexed);

    EXPECT_EQ(decision.action, ControlAction::DownloadBook);
    EXPECT_EQ(decision.book_id, 5);
}

TEST(NextControlAction, SkipsAlreadyDownloadedCandidatesWhenPickingWhatToDownload) {
    TempDir root("stage1_next_control_action_test_skip");
    ControlLog downloaded(root.path() / "downloaded_books.txt");
    ControlLog indexed(root.path() / "indexed_books.txt");
    downloaded.mark(5);
    indexed.mark(5);  // already fully processed: not a candidate for anything

    auto decision = next_control_action({5, 1342, 999}, downloaded, indexed);

    EXPECT_EQ(decision.action, ControlAction::DownloadBook);
    EXPECT_EQ(decision.book_id, 1342);
}

TEST(NextControlAction, PrefersIndexingOverDownloadingWhenABookIsReady) {
    TempDir root("stage1_next_control_action_test_prefer_index");
    ControlLog downloaded(root.path() / "downloaded_books.txt");
    ControlLog indexed(root.path() / "indexed_books.txt");
    downloaded.mark(5);
    downloaded.mark(1342);
    indexed.mark(5);
    // 1342 is downloaded but not indexed; 999 is not even downloaded yet.

    auto decision = next_control_action({5, 1342, 999}, downloaded, indexed);

    EXPECT_EQ(decision.action, ControlAction::IndexBook);
    EXPECT_EQ(decision.book_id, 1342);
}

TEST(NextControlAction, IndexingTakesPriorityEvenWhenOtherCandidatesCouldStillBeDownloaded) {
    TempDir root("stage1_next_control_action_test_priority");
    ControlLog downloaded(root.path() / "downloaded_books.txt");
    ControlLog indexed(root.path() / "indexed_books.txt");
    downloaded.mark(5);  // downloaded but not indexed
    // 1342 has never been downloaded at all.

    auto decision = next_control_action({5, 1342}, downloaded, indexed);

    EXPECT_EQ(decision.action, ControlAction::IndexBook);
    EXPECT_EQ(decision.book_id, 5);
}

TEST(NextControlAction, ReturnsNothingWhenEveryCandidateIsDownloadedAndIndexed) {
    TempDir root("stage1_next_control_action_test_done");
    ControlLog downloaded(root.path() / "downloaded_books.txt");
    ControlLog indexed(root.path() / "indexed_books.txt");
    for (int id : {5, 1342, 999}) {
        downloaded.mark(id);
        indexed.mark(id);
    }

    auto decision = next_control_action({5, 1342, 999}, downloaded, indexed);

    EXPECT_EQ(decision.action, ControlAction::Nothing);
}
