#include "stage1/metadata_insert_benchmark.hpp"

#include <algorithm>
#include <memory>
#include <stdexcept>

#include "stage1/metadata.hpp"
#include "stage1/metadata_store.hpp"

namespace stage1 {

std::vector<BenchmarkResult> benchmark_metadata_insert(const std::string& language,
                                                         const std::vector<SampleBook>& books,
                                                         const std::filesystem::path& output_dir) {
    const auto db_path = output_dir / "metadata.db";

    std::unique_ptr<MetadataStore> store;
    const auto setup = [&] {
        std::filesystem::remove(db_path);
        store = std::make_unique<MetadataStore>(db_path);  // opens + creates schema: untimed on purpose
    };
    const auto operation = [&] {
        for (const auto& book : books) {
            const BookMetadata metadata = extract_metadata(book.header);
            const std::string body_path = "datalake/" + std::to_string(book.book_id) + "/body.txt";
            const std::string header_path = "datalake/" + std::to_string(book.book_id) + "/header.txt";
            store->insert_book(book.book_id, metadata, body_path, header_path);
        }
    };

    const auto elapsed = measure_elapsed_ms(setup, operation);

    for (const auto& book : books) {
        if (!store->find_by_id(book.book_id)) {
            throw std::runtime_error("metadata_insert: book " + std::to_string(book.book_id) +
                                      " was not inserted");
        }
    }

    const int dataset_size = static_cast<int>(books.size());
    std::vector<BenchmarkResult> results;
    int repetition = 1;
    for (double ms : elapsed) {
        results.push_back(
            BenchmarkResult{language, "metadata_insert", "sqlite", dataset_size, repetition, "elapsed", ms, "ms"});
        const double rows_per_second = dataset_size / (std::max(ms, 0.001) / 1000.0);
        results.push_back(BenchmarkResult{language, "metadata_insert", "sqlite", dataset_size, repetition,
                                           "throughput", rows_per_second, "rows_per_s"});
        ++repetition;
    }
    return results;
}

}  // namespace stage1
