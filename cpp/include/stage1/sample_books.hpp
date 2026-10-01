#pragma once

#include <string>
#include <vector>

#include "stage1/control_log.hpp"
#include "stage1/metadata_store.hpp"

namespace stage1 {

// An already-downloaded, already header/body-split book, ready to feed a
// benchmark. SPEC section 9: benchmarks that build/write structures must
// start from books already on disk, so the network never contaminates the
// timings.
struct SampleBook {
    int book_id;
    std::string body;
};

// Loads every book in `candidate_ids` that `downloaded` already has recorded,
// reading its body straight off disk via the path `metadata` stored for it --
// the same path every Datalake layout returns from write(). No network
// involved: this is meant to run against books a real pipeline run already
// downloaded (see main.cpp's `pipeline <N>`), the real-text counterpart to
// generating synthetic benchmark data.
std::vector<SampleBook> load_sample_books(const std::vector<int>& candidate_ids, const ControlLog& downloaded,
                                           const MetadataStore& metadata);

}  // namespace stage1
