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

// Disk space reserved for everything under `root` (root included), with the
// Java module's own estimate (DatalakeBenchmark.allocatedBytes): every file
// takes its size rounded up to whole filesystem blocks, every directory one
// block. Thousands of small files reserve far more than their bytes add up
// to. Throws std::runtime_error if the filesystem cannot be queried.
long long allocated_bytes(const std::filesystem::path& root);

// Runs, once per repetition (`warmup_runs` discarded, then `measured_runs`
// measured): `setup()` untimed, then `operation()` timed with a steady clock.
// Useful when every repetition must start from the same prepared state (e.g.
// "a datalake with some books already missing") without that preparation
// cost leaking into the measured time -- unlike a repetition that can simply
// redo its own work from scratch (building an index, writing files), some
// operations (like recovering from damage) have nothing left to do once
// already run once, so each repetition needs the damage reintroduced first.
// Defaults are SPEC section 9's shared methodology: N_WARMUP=2, N_RUNS=5.
// Returns each measured run's elapsed time, in milliseconds.
std::vector<double> measure_elapsed_ms(const std::function<void()>& setup, const std::function<void()>& operation,
                                        int warmup_runs = 2, int measured_runs = 5);

// Convenience overload for the common case: nothing needs to be (re)done
// between repetitions.
std::vector<double> measure_elapsed_ms(const std::function<void()>& operation, int warmup_runs = 2,
                                        int measured_runs = 5);

}  // namespace stage1
