#pragma once

#include <string>
#include <unordered_set>
#include <vector>

#include "stage1/crawler/book_source.hpp"
#include "stage1/control/control_log.hpp"
#include "stage1/datalake/datalake.hpp"
#include "stage1/datamart/index/index_writer.hpp"
#include "stage1/datamart/index/inverted_index.hpp"
#include "stage1/datamart/metadata/metadata_store.hpp"

namespace stage1 {

// What one call to run_pipeline_step actually did. `decision` is what the
// control layer chose (next_control_action); `completed` says whether that
// action finished and the book was marked. When it did not, `failure` says
// why (the fetch error, missing START/END markers...), and the book stays
// unmarked, so a later run retries it. A Nothing step counts as completed.
struct StepResult {
    ControlDecision decision;
    bool completed;
    std::string failure;  // empty when completed
};

// Runs exactly one step of the pipeline (shared/SPEC.md section 8.2): asks
// next_control_action what to do, then performs it against the concrete
// components passed in.
//
//  - DownloadBook: fetch the book, split it into header/body (discarded if the
//    markers are missing, per SPEC section 2 -- and, either way, never marked
//    downloaded, so it is retried on a future run), write it to `datalake`,
//    extract and store its metadata, then mark it downloaded. A failed fetch is
//    likewise never marked, for the same reason.
//  - IndexBook: read the book's body back from the path MetadataStore stored,
//    tokenize it with `stopwords`, add it to `index`, persist that book's
//    distinct terms through `index_writer.update_terms`, then mark it indexed.
//  - Nothing: every candidate is already downloaded and indexed.
//
// Every dependency is passed in by reference (the same dependency-injection
// shape used throughout this project) so this function can be tested against
// fakes and temporary directories, with no real network or a real database.
StepResult run_pipeline_step(const std::vector<int>& candidate_ids, ControlLog& downloaded, ControlLog& indexed,
                              BookSource& source, Datalake& datalake, MetadataStore& metadata, InvertedIndex& index,
                              IndexWriter& index_writer, const std::unordered_set<std::string>& stopwords);

}  // namespace stage1
