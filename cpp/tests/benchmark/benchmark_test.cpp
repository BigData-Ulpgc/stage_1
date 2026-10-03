#include <gtest/gtest.h>

#include <fstream>
#include <sstream>

#include "stage1/benchmark/benchmark.hpp"
#include "support/temp_dir.hpp"

using stage1::allocated_bytes;
using stage1::BenchmarkResult;
using stage1::derived_rows;
using stage1::elapsed_rows;
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

TEST(AllocatedBytes, RoundsEveryFileUpToWholeBlocksAndCountsOneBlockPerDirectory) {
    TempDir root("stage1_benchmark_test_allocated");
    const long long block = allocated_bytes(root.path());  // an empty directory: exactly one block

    std::filesystem::create_directories(root.path() / "sub");
    std::ofstream(root.path() / "tiny.txt") << "x";                                    // 1 byte -> 1 block
    std::ofstream(root.path() / "sub" / "bigger.txt") << std::string(block + 1, 'y');  // block+1 -> 2 blocks

    // root + sub (2 directories) + 1 block + 2 blocks
    EXPECT_EQ(allocated_bytes(root.path()), 5 * block);
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

TEST(MeasureElapsedMs, SetupRunsOnceBeforeEveryRepetitionUntimed) {
    int setup_calls = 0;
    int operation_calls = 0;
    measure_elapsed_ms([&setup_calls] { ++setup_calls; }, [&operation_calls] { ++operation_calls; }, 2, 5);

    EXPECT_EQ(setup_calls, 2 + 5);
    EXPECT_EQ(operation_calls, 2 + 5);
}

TEST(MeasureElapsedMs, SetupStateIsVisibleToOperation) {
    int shared_counter = 0;
    auto results = measure_elapsed_ms([&shared_counter] { shared_counter = 10; },
                                       [&shared_counter] { shared_counter += 1; }, 1, 3);

    // Each repetition resets shared_counter to 10 via setup, then operation
    // adds 1: if setup ran before every repetition (not just once overall),
    // the final value must be 11, not 10 + (1+3) = 14.
    EXPECT_EQ(shared_counter, 11);
    EXPECT_EQ(results.size(), 3u);
}

TEST(ElapsedRows, NumbersTheRepetitionsFromOne) {
    const auto rows = elapsed_rows("cpp", "datalake_write", "book", 200, {5.0, 7.5});

    ASSERT_EQ(rows.size(), 2u);
    EXPECT_EQ(rows[0].repetition, 1);
    EXPECT_EQ(rows[0].metric, "elapsed");
    EXPECT_EQ(rows[0].unit, "ms");
    EXPECT_EQ(rows[1].repetition, 2);
    EXPECT_EQ(rows[1].value, 7.5);
    EXPECT_EQ(rows[1].dataset_size, 200);
}

TEST(DerivedRows, KeepEachRepetitionAndNeverDivideByZero) {
    const auto elapsed = elapsed_rows("cpp", "datalake_write", "book", 10, {0.0, 2.0});

    const auto rows =
        derived_rows(elapsed, "throughput", "books_per_s", [](double ms) { return 10 / (ms / 1000.0); });

    ASSERT_EQ(rows.size(), 2u);
    EXPECT_EQ(rows[0].repetition, 1);
    EXPECT_EQ(rows[0].metric, "throughput");
    EXPECT_EQ(rows[0].unit, "books_per_s");
    EXPECT_DOUBLE_EQ(rows[0].value, 10 / (0.001 / 1000.0));  // 0 ms counts as 0.001 ms, as in Java
    EXPECT_EQ(rows[1].repetition, 2);
    EXPECT_DOUBLE_EQ(rows[1].value, 5000.0);
    EXPECT_EQ(rows[1].structure, "book");
}
