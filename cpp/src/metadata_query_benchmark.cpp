#include "stage1/metadata_query_benchmark.hpp"

#include <random>
#include <stdexcept>

#include "stage1/metadata.hpp"
#include "stage1/metadata_store.hpp"

namespace stage1 {

namespace {

// Times running every value in `workload` through `query` as one block per
// measure_elapsed_ms repetition, accumulating how many results each lookup
// found (so the call is never optimized away, and so a query type that
// silently stopped finding anything is caught). Appends an "elapsed" row
// (total ms for the whole workload) and a derived "<metric>_avg" row
// (microseconds per single lookup) per measured run.
template <typename T, typename QueryFn>
void run_query_type(const std::string& language, int dataset_size, const std::string& metric,
                     const std::vector<T>& workload, QueryFn query, std::vector<BenchmarkResult>& results) {
    long long found = 0;
    const auto elapsed = measure_elapsed_ms([&] {
        found = 0;
        for (const auto& value : workload) {
            found += query(value);
        }
    });

    if (found < static_cast<long long>(workload.size())) {
        throw std::runtime_error(metric + ": some queries in the workload found nothing");
    }

    int repetition = 1;
    for (double ms : elapsed) {
        results.push_back(
            BenchmarkResult{language, "metadata_query", "sqlite", dataset_size, repetition, metric, ms, "ms"});
        const double microseconds_per_query = ms * 1000.0 / static_cast<double>(workload.size());
        results.push_back(BenchmarkResult{language, "metadata_query", "sqlite", dataset_size, repetition,
                                           metric + "_avg", microseconds_per_query, "us"});
        ++repetition;
    }
}

}  // namespace

std::vector<BenchmarkResult> benchmark_metadata_query(const std::string& language,
                                                        const std::vector<SampleBook>& books,
                                                        const std::filesystem::path& output_dir, int query_count) {
    std::vector<BookMetadata> metadata;
    metadata.reserve(books.size());
    for (const auto& book : books) {
        BookMetadata parsed = extract_metadata(book.header);
        if (!parsed.title || !parsed.author) {
            throw std::invalid_argument("benchmark_metadata_query needs every book to have a title and an author");
        }
        metadata.push_back(std::move(parsed));
    }

    const auto db_path = output_dir / "metadata.db";
    std::filesystem::remove(db_path);
    MetadataStore store(db_path);
    store.begin_transaction();  // a one-off bulk load: see Entry 37
    for (std::size_t i = 0; i < books.size(); ++i) {
        const std::string body_path = "datalake/" + std::to_string(books[i].book_id) + "/body.txt";
        const std::string header_path = "datalake/" + std::to_string(books[i].book_id) + "/header.txt";
        store.insert_book(books[i].book_id, metadata[i], body_path, header_path);
    }
    store.commit_transaction();

    // Fixed seed: the same workload every run, so results are comparable
    // from one execution to the next (same spirit as Java's `new Random(42)`,
    // though not the same algorithm -- bit-for-bit identical picks across
    // languages was never the point, only "deterministic, not flaky").
    std::mt19937 rng(42);
    std::uniform_int_distribution<std::size_t> pick(0, books.size() - 1);

    std::vector<int> id_workload;
    std::vector<std::string> author_workload;
    std::vector<std::string> title_workload;
    id_workload.reserve(query_count);
    author_workload.reserve(query_count);
    title_workload.reserve(query_count);
    for (int i = 0; i < query_count; ++i) {
        const std::size_t index = pick(rng);
        id_workload.push_back(books[index].book_id);
        author_workload.push_back(*metadata[index].author);
        title_workload.push_back(*metadata[index].title);
    }

    const int dataset_size = static_cast<int>(books.size());
    std::vector<BenchmarkResult> results;

    run_query_type(language, dataset_size, "find_by_id", id_workload,
                   [&](int id) { return store.find_by_id(id) ? 1 : 0; }, results);
    run_query_type(language, dataset_size, "find_by_author", author_workload,
                   [&](const std::string& author) { return static_cast<int>(store.find_by_author(author).size()); },
                   results);
    run_query_type(language, dataset_size, "find_by_title", title_workload,
                   [&](const std::string& title) { return static_cast<int>(store.find_by_title(title).size()); },
                   results);

    return results;
}

}  // namespace stage1
