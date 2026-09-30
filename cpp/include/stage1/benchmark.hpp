#pragma once

#include <filesystem>
#include <functional>
#include <string>
#include <vector>

namespace stage1 {

// One row of the shared benchmark CSV format (shared/SPEC.md section 9):
// language,experiment,structure,dataset_size,repetition,metric,value,unit
struct BenchmarkResult {
    std::string language;
    std::string experiment;
    std::string structure;
    int dataset_size;
    int repetition;  // 1-based, over the N_RUNS *measured* repetitions only
    std::string metric;
    double value;
    std::string unit;
};

// Writes `results` as CSV to `path`, with the shared header, creating missing
// parent directories. SPEC section 9's own naming convention is one file per
// (language, experiment), e.g. benchmarks/results/cpp_index_build.csv.
void write_benchmark_results(const std::filesystem::path& path, const std::vector<BenchmarkResult>& results);

// Runs `operation` `warmup_runs` times (discarded, to let caches/allocators
// settle) then `measured_runs` times, timing each measured run with a steady
// clock. Defaults are SPEC section 9's shared methodology: N_WARMUP=2,
// N_RUNS=5. Returns each measured run's elapsed time, in milliseconds.
std::vector<double> measure_elapsed_ms(const std::function<void()>& operation, int warmup_runs = 2,
                                        int measured_runs = 5);

}  // namespace stage1
