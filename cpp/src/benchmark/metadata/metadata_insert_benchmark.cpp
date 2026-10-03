#include "stage1/benchmark/metadata/metadata_insert_benchmark.hpp"

#include <algorithm>
#include <memory>
#include <stdexcept>

namespace stage1 {

std::vector<BenchmarkResult> benchmark_metadata_insert(const std::string& language,
                                                         const std::vector<StoredBook>& rows,
                                                         const std::filesystem::path& output_dir,
                                                         std::size_t batch_size) {
    if (batch_size == 0) {
        throw std::invalid_argument("benchmark_metadata_insert needs a batch size of at least 1");
    }
    // Split before anything is timed, as Java's batches().
    std::vector<std::vector<StoredBook>> batches;
    for (std::size_t from = 0; from < rows.size(); from += batch_size) {
        const std::size_t to = std::min(from + batch_size, rows.size());
        batches.emplace_back(rows.begin() + static_cast<std::ptrdiff_t>(from),
                             rows.begin() + static_cast<std::ptrdiff_t>(to));
    }

    const int dataset_size = static_cast<int>(rows.size());
    std::vector<BenchmarkResult> results;

    for (const auto& structure : kMetadataStructures) {
        const auto db = output_dir / "insert" / (structure + "_" + std::to_string(dataset_size) + ".db");
        std::unique_ptr<MetadataStore> store;
        const auto elapsed_ms = measure_elapsed_ms(
            [&] {  // untimed: an empty database every repetition
                store.reset();  // the previous one is closed first, as Java does
                store = fresh_metadata_store(structure, db);
            },
            [&] {
                for (const auto& batch : batches) {
                    store->insert_books(batch);
                }
            });
        if (store->count() != dataset_size) {
            throw std::runtime_error(structure + ": metadata_insert did not insert every row");
        }

        const auto elapsed = elapsed_rows(language, "metadata_insert", structure, dataset_size, elapsed_ms);
        const auto throughput = derived_rows(elapsed, "throughput", "rows_per_s",
                                             [&](double ms) { return dataset_size / (ms / 1000.0); });
        results.insert(results.end(), elapsed.begin(), elapsed.end());
        results.insert(results.end(), throughput.begin(), throughput.end());
    }
    return results;
}

}  // namespace stage1
