#include "stage1/benchmark/benchmark.hpp"

#include <sys/statvfs.h>

#include <chrono>
#include <iomanip>
#include <sstream>
#include <stdexcept>
#include <string>

#include "stage1/util/file_io.hpp"

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

long long allocated_bytes(const std::filesystem::path& root) {
    struct statvfs filesystem{};
    if (statvfs(root.c_str(), &filesystem) != 0) {
        throw std::runtime_error("cannot query the filesystem of " + root.string());
    }
    const long long block = static_cast<long long>(filesystem.f_frsize != 0 ? filesystem.f_frsize : filesystem.f_bsize);
    const auto in_blocks = [block](long long size) { return (size + block - 1) / block * block; };

    long long total = in_blocks(1);  // `root` itself is a directory: one block
    for (const auto& entry : std::filesystem::recursive_directory_iterator(root)) {
        total += in_blocks(entry.is_directory() ? 1 : static_cast<long long>(entry.file_size()));
    }
    return total;
}

std::vector<double> measure_elapsed_ms(const std::function<void()>& setup, const std::function<void()>& operation,
                                        int warmup_runs, int measured_runs) {
    for (int i = 0; i < warmup_runs; ++i) {
        setup();
        operation();
    }

    std::vector<double> elapsed_ms;
    elapsed_ms.reserve(measured_runs);
    for (int i = 0; i < measured_runs; ++i) {
        setup();
        const auto start = std::chrono::steady_clock::now();
        operation();
        const auto end = std::chrono::steady_clock::now();
        elapsed_ms.push_back(std::chrono::duration<double, std::milli>(end - start).count());
    }
    return elapsed_ms;
}

std::vector<double> measure_elapsed_ms(const std::function<void()>& operation, int warmup_runs, int measured_runs) {
    return measure_elapsed_ms([] {}, operation, warmup_runs, measured_runs);
}

}  // namespace stage1
