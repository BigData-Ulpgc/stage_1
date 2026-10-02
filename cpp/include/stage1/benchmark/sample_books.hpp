#pragma once

#include <string>
#include <vector>

#include "stage1/control/control_log.hpp"
#include "stage1/datamart/metadata/metadata_store.hpp"

namespace stage1 {

// An already-downloaded, already header/body-split book, ready to feed a
// benchmark. SPEC section 9: benchmarks that build/write structures must
// start from books already on disk, so the network never contaminates the
// timings.
// `header` is appended last, not inserted between `book_id` and `body`, so
// that existing two-value aggregate-init literals like `{1, "some body"}`
// (several tests already use this shape) keep meaning what they always did,
// with `header` simply defaulting to an empty string.
struct SampleBook {
    int book_id;
    std::string body;
    std::string header;
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
