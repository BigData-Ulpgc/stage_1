#pragma once

#include <filesystem>
#include <unordered_set>
#include <vector>

namespace stage1 {

// A control file (shared/SPEC.md section 8): one book id per line, tracking
// which books have reached a particular stage of the pipeline (downloaded,
// indexed, ...). An id must only be recorded with mark() *after* the work it
// represents has fully and successfully completed -- see DEVLOG for why: this
// is what lets the pipeline resume after a crash without losing or
// duplicating work.
class ControlLog {
public:
    explicit ControlLog(std::filesystem::path path);

    // True if `book_id` has already been recorded.
    bool contains(int book_id) const;

    // Every recorded id, ascending (each once). Includes ids recorded in a
    // previous run that are no longer in shared/book_ids.txt: this reports
    // what the log holds, not what the current dataset expects.
    std::vector<int> ids() const;

    // Appends `book_id` to the log, on disk and in memory. A no-op if it was
    // already recorded, so calling it more than once for the same id never
    // duplicates a line (SPEC section 8: "recuperación sin pérdidas ni
    // duplicados").
    void mark(int book_id);

private:
    std::filesystem::path path_;
    std::unordered_set<int> recorded_;
};

// What the control layer decides to do next (shared/SPEC.md section 8.2).
enum class ControlAction {
    IndexBook,     // book_id was downloaded but not indexed yet
    DownloadBook,  // book_id has not been downloaded yet
    Nothing        // every candidate is both downloaded and indexed
};

struct ControlDecision {
    ControlAction action;
    int book_id;  // meaningful only when action != ControlAction::Nothing
};

// Decides the pipeline's next step, pure with respect to I/O (it only reads
// `downloaded`/`indexed`, it performs no action itself):
//  1. If any id in `candidate_ids` is downloaded but not indexed, index the
//     first such id, in `candidate_ids` order (indexing has priority over
//     downloading more books, same order as the PDF's own control_pipeline_step).
//  2. Otherwise, download the first id in `candidate_ids` not yet downloaded.
//  3. If every candidate is downloaded and indexed, there is nothing to do.
ControlDecision next_control_action(const std::vector<int>& candidate_ids, const ControlLog& downloaded,
                                     const ControlLog& indexed);

}  // namespace stage1
