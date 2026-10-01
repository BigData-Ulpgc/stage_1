#pragma once

#include <string>

namespace stage1 {

// Implements `search_engine_stage1 pipeline <N>`: downloads and indexes up
// to `steps` books, resuming from whatever shared/book_ids.txt and the
// control logs under data/ already reflect. Returns the process exit code.
int run_pipeline_command(int steps);

// Implements `search_engine_stage1 benchmark <experiment>`: runs one SPEC
// section 9 experiment against books a previous `pipeline <N>` run already
// downloaded, and writes benchmarks/results/cpp_<experiment>.csv. Returns
// the process exit code.
int run_benchmark_command(const std::string& experiment);

}  // namespace stage1
