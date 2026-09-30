#include "stage1/benchmark.hpp"

#include <chrono>
#include <iomanip>
#include <sstream>

#include "stage1/file_io.hpp"

namespace stage1 {

void write_benchmark_results(const std::filesystem::path& path, const std::vector<BenchmarkResult>& results) {
    std::ostringstream out;
    out << "language,experiment,structure,dataset_size,repetition,metric,value,unit\n";
    // Fixed precision, not scientific notation: three decimals keeps
    // microsecond resolution on millisecond-scale timings without a format
    // (e.g. "1.23e+04") that would need special handling to load as a plain
    // number in a spreadsheet or a CSV-reading benchmark script.
    out << std::fixed << std::setprecision(3);
    for (const auto& result : results) {
        out << result.language << ',' << result.experiment << ',' << result.structure << ',' << result.dataset_size
            << ',' << result.repetition << ',' << result.metric << ',' << result.value << ',' << result.unit << '\n';
    }
    // Safe without CSV quoting: every field here is one of our own fixed
    // identifiers ("cpp", "index_build", "monolithic", "ms", ...), never text
    // from an external source (like a book title) that could contain a comma.
    write_text_file(path, out.str());
}

std::vector<double> measure_elapsed_ms(const std::function<void()>& operation, int warmup_runs, int measured_runs) {
    for (int i = 0; i < warmup_runs; ++i) {
        operation();
    }

    std::vector<double> elapsed_ms;
    elapsed_ms.reserve(measured_runs);
    for (int i = 0; i < measured_runs; ++i) {
        const auto start = std::chrono::steady_clock::now();
        operation();
        const auto end = std::chrono::steady_clock::now();
        elapsed_ms.push_back(std::chrono::duration<double, std::milli>(end - start).count());
    }
    return elapsed_ms;
}

}  // namespace stage1
