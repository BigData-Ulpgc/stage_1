#pragma once

#include <string>

namespace stage1 {

// Implements `search_engine_stage1 pipeline <N> [--offline]`: downloads and indexes up
// to `steps` books, resuming from whatever shared/book_ids.txt and the
// control logs under data/ already reflect. Returns the process exit code.
//
// With `offline`, the books come from sample_dataset/ instead of Project
// Gutenberg: its 15 ids (sample_dataset/book_ids.txt), read from
// sample_dataset/raw/ with LocalFileSource. No network is used, and
// everything after fetching a book stays exactly the same.
int run_pipeline_command(int steps, bool offline);

// Implements `search_engine_stage1 search <words...>`: an AND query (SPEC
// section 7) answered straight from the inverted index `pipeline` persisted
// under data/datamarts/, never from the books themselves. Prints each
// matching book's id and title (from the metadata database). Returns the
// process exit code.
int run_search_command(const std::string& query);

// Implements `search_engine_stage1 status`: how many book ids the dataset
// has, how many the control logs under data/ record as downloaded and as
// indexed, and which are downloaded but not indexed yet. Changes nothing
// (beyond ControlLog creating an empty data/control/ the very first time).
// Returns the process exit code.
int run_status_command();

// Implements `search_engine_stage1 benchmark <experiment>`: runs one SPEC
// section 9 experiment -- the datalake and index ones against books a
// previous `pipeline <N>` run already downloaded, the metadata ones on
// synthetic rows at N=1,000/10,000/100,000 (SPEC section 10.2) -- and writes
// benchmarks/results/<real|synthetic>/<category>/cpp_<experiment>.csv.
// Returns the process exit code.
int run_benchmark_command(const std::string& experiment);

}  // namespace stage1
