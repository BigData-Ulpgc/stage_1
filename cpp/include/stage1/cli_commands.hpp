#pragma once

#include <string>

namespace stage1 {

// Implements `search_engine_stage1 pipeline <N>`: downloads and indexes up
// to `steps` books, resuming from whatever shared/book_ids.txt and the
// control logs under data/ already reflect. Returns the process exit code.
int run_pipeline_command(int steps);

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
// section 9 experiment against books a previous `pipeline <N>` run already
// downloaded, and writes benchmarks/results/cpp_<experiment>.csv. Returns
// the process exit code.
int run_benchmark_command(const std::string& experiment);

}  // namespace stage1
