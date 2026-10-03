#include <gtest/gtest.h>

#include <fstream>
#include <sstream>

#include "stage1/benchmark.hpp"
#include "support/temp_dir.hpp"

using stage1::BenchmarkResult;
using stage1::measure_elapsed_ms;
using stage1::testing::TempDir;
using stage1::write_benchmark_results;

namespace {

std::string read_file(const std::filesystem::path& path) {
    std::ifstream file(path, std::ios::binary);
    std::ostringstream contents;
    contents << file.rdbuf();
    return contents.str();
}

}  // namespace

TEST(WriteBenchmarkResults, WritesTheSharedHeaderAndOneRowPerResult) {
    TempDir root("stage1_benchmark_test_write");
    const auto path = root.path() / "cpp_index_build.csv";

    write_benchmark_results(path, {
                                       BenchmarkResult{"cpp", "index_build", "monolithic", 100, 1, "elapsed", 1234.5, "ms"},
                                       BenchmarkResult{"cpp", "index_build", "monolithic", 100, 2, "elapsed", 1180.2, "ms"},
                                   });

    EXPECT_EQ(read_file(path),
              "language,experiment,structure,dataset_size,repetition,metric,value,unit\n"
              "cpp,index_build,monolithic,100,1,elapsed,1234.500,ms\n"
              "cpp,index_build,monolithic,100,2,elapsed,1180.200,ms\n");
}

TEST(WriteBenchmarkResults, EmptyResultsStillWritesTheHeader) {
    TempDir root("stage1_benchmark_test_empty");
    const auto path = root.path() / "cpp_index_build.csv";

    write_benchmark_results(path, {});

    EXPECT_EQ(read_file(path), "language,experiment,structure,dataset_size,repetition,metric,value,unit\n");
}

TEST(WriteBenchmarkResults, CreatesMissingParentDirectories) {
    TempDir root("stage1_benchmark_test_dirs");
    const auto path = root.path() / "benchmarks" / "results" / "cpp_index_build.csv";

    write_benchmark_results(path, {});

    EXPECT_TRUE(std::filesystem::exists(path));
}

TEST(MeasureElapsedMs, ReturnsExactlyTheMeasuredRunCount) {
    auto results = measure_elapsed_ms([] {}, /*warmup_runs=*/2, /*measured_runs=*/5);
    EXPECT_EQ(results.size(), 5u);
}

TEST(MeasureElapsedMs, DefaultsMatchTheSpecMethodology) {
    int calls = 0;
    measure_elapsed_ms([&calls] { ++calls; });  // no explicit warmup_runs/measured_runs
    EXPECT_EQ(calls, 2 + 5);                     // SPEC section 9: N_WARMUP=2, N_RUNS=5
}

TEST(MeasureElapsedMs, CallsOperationWarmupPlusMeasuredTimesInTotal) {
    int calls = 0;
    measure_elapsed_ms([&calls] { ++calls; }, /*warmup_runs=*/3, /*measured_runs=*/4);
    EXPECT_EQ(calls, 3 + 4);
}

TEST(MeasureElapsedMs, EveryMeasuredValueIsNonNegative) {
    auto results = measure_elapsed_ms([] {}, 1, 5);
    for (double value : results) {
        EXPECT_GE(value, 0.0);
    }
}
