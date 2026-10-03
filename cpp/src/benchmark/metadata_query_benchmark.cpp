#include "stage1/benchmark/metadata_query_benchmark.hpp"

#include <stdexcept>

#include "stage1/benchmark/java_random.hpp"

namespace stage1 {

namespace {

// Times running every value in `workload` through `query` as one block per
// repetition, adding up what each lookup found: the result is used, and a
// query type that silently stopped finding anything is caught (Java's check).
// Appends, per measured repetition, a `metric` row (ms, the whole workload)
// and a `<metric>_avg` row (us per query).
template <typename T, typename QueryFn>
void run_query_type(const std::string& language, const std::string& structure, int dataset_size,
                    const std::string& metric, const std::vector<T>& workload, QueryFn query,
                    std::vector<BenchmarkResult>& results) {
    long long found = 0;
    const auto elapsed_ms = measure_elapsed_ms([&] { found = 0; },
                                               [&] {
                                                   for (const auto& value : workload) {
                                                       found += query(value);
                                                   }
                                               });
    if (found < static_cast<long long>(workload.size())) {
        throw std::runtime_error(structure + " " + metric + ": some query found nothing");
    }

    int repetition = 1;
    for (double ms : elapsed_ms) {
        results.push_back(
            BenchmarkResult{language, "metadata_query", structure, dataset_size, repetition, metric, ms, "ms"});
        results.push_back(BenchmarkResult{language, "metadata_query", structure, dataset_size, repetition,
                                           metric + "_avg", ms * 1000.0 / static_cast<double>(workload.size()),
                                           "us"});
        ++repetition;
    }
}

}  // namespace

std::vector<BenchmarkResult> benchmark_metadata_query(const std::string& language,
                                                        const std::vector<StoredBook>& rows,
                                                        const std::filesystem::path& output_dir, int query_count) {
    if (rows.empty() || query_count < 1) {
        throw std::invalid_argument("benchmark_metadata_query needs at least one row and one query");
    }

    // Java's workload: each query picks a row with new Random(42).nextInt(N).
    JavaRandom random(42);
    std::vector<int> ids;
    std::vector<std::string> authors;
    std::vector<std::string> titles;
    for (int i = 0; i < query_count; ++i) {
        const StoredBook& pick = rows[static_cast<std::size_t>(random.next_int(static_cast<int>(rows.size())))];
        if (!pick.title || !pick.author) {
            throw std::invalid_argument("benchmark_metadata_query needs every row to have a title and an author");
        }
        ids.push_back(pick.book_id);
        authors.push_back(*pick.author);
        titles.push_back(*pick.title);
    }

    const int dataset_size = static_cast<int>(rows.size());
    std::vector<BenchmarkResult> results;

    for (const auto& structure : kMetadataStructures) {
        const auto db = output_dir / "query" / (structure + "_" + std::to_string(dataset_size) + ".db");
        const auto store = fresh_metadata_store(structure, db);
        store->insert_books(rows);  // one-off preparation, not timed

        run_query_type(language, structure, dataset_size, "find_by_id", ids,
                       [&](int id) { return store->find_by_id(id) ? 1 : 0; }, results);
        run_query_type(language, structure, dataset_size, "find_by_author", authors,
                       [&](const std::string& author) { return static_cast<int>(store->find_by_author(author).size()); },
                       results);
        run_query_type(language, structure, dataset_size, "find_by_title", titles,
                       [&](const std::string& title) { return static_cast<int>(store->find_by_title(title).size()); },
                       results);
    }
    return results;
}

}  // namespace stage1
