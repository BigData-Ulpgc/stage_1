// Thin CLI entry point: wires the real components together and drives either
// run_pipeline_step (src/pipeline.cpp) or a benchmark experiment. All the
// actual logic lives in stage1_core, already covered by its own unit tests;
// this file has none of its own by design, mirroring the Java module's own
// `pipeline <N>` command.
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <string_view>

#include "stage1/book_based_datalake.hpp"
#include "stage1/book_id_list.hpp"
#include "stage1/control_log.hpp"
#include "stage1/curl_http_client.hpp"
#include "stage1/datalake_incremental_benchmark.hpp"
#include "stage1/datalake_recovery_benchmark.hpp"
#include "stage1/datalake_storage_benchmark.hpp"
#include "stage1/datalake_lookup_benchmark.hpp"
#include "stage1/datalake_write_benchmark.hpp"
#include "stage1/file_io.hpp"
#include "stage1/gutenberg_client.hpp"
#include "stage1/index_build_benchmark.hpp"
#include "stage1/index_query_benchmark.hpp"
#include "stage1/inverted_index.hpp"
#include "stage1/metadata_store.hpp"
#include "stage1/monolithic_index_writer.hpp"
#include "stage1/pipeline.hpp"
#include "stage1/query_list.hpp"
#include "stage1/sample_books.hpp"
#include "stage1/stopwords.hpp"
#include "stage1/tokenizer.hpp"

namespace {

// Fixed at compile time (see CMakeLists.txt), so the binary finds shared/ and
// its own data/ and benchmarks/ directories regardless of the current working
// directory it is launched from.
const std::filesystem::path kSharedDir = STAGE1_SHARED_DIR;
const std::filesystem::path kDataDir = STAGE1_DATA_DIR;
const std::filesystem::path kBenchmarksDir = STAGE1_BENCHMARKS_DIR;

void print_usage() {
    std::cerr << "usage: search_engine_stage1 pipeline <N>\n"
                 "       search_engine_stage1 benchmark "
                 "<datalake_write|datalake_lookup|datalake_incremental|datalake_recovery|"
                 "datalake_storage|index_build|index_query>\n";
}

void describe(const stage1::ControlDecision& decision) {
    switch (decision.action) {
        case stage1::ControlAction::DownloadBook:
            std::cout << "[pipeline] downloaded book " << decision.book_id << "\n";
            break;
        case stage1::ControlAction::IndexBook:
            std::cout << "[pipeline] indexed book " << decision.book_id << "\n";
            break;
        case stage1::ControlAction::Nothing:
            std::cout << "[pipeline] nothing left to do\n";
            break;
    }
}

// Runs `steps` pipeline steps. The datalake layout (book-based here) and the
// index format (monolithic JSON here) are each one of three SPEC-required
// alternatives; both sit behind their own interface (Datalake, IndexWriter),
// so swapping either for a benchmark run means changing these two lines, not
// anything in stage1_core.
int run_pipeline(int steps) {
    const auto stopwords = stage1::load_stopwords(kSharedDir / "stopwords.txt");
    const auto candidate_ids = stage1::load_book_ids(kSharedDir / "book_ids.txt");

    stage1::ControlLog downloaded(kDataDir / "control" / "downloaded_books.txt");
    stage1::ControlLog indexed(kDataDir / "control" / "indexed_books.txt");

    stage1::CurlHttpClient http_client;
    stage1::GutenbergSource source(http_client);
    stage1::BookBasedDatalake datalake(kDataDir / "datalake" / "book");
    stage1::MetadataStore metadata(kDataDir / "datamarts" / "metadata.db");
    stage1::MonolithicIndexWriter index_writer(kDataDir / "datamarts" / "inverted_index.json");

    // Rebuilt from scratch on every run by re-reading each already-indexed
    // book's body: this stage has no reader for the on-disk index formats
    // (only writers), so this is the simplest correct way to resume with a
    // populated in-memory index. Known cost, worth revisiting once the
    // project needs to resume large runs often (see DEVLOG).
    stage1::InvertedIndex index;
    for (int book_id : candidate_ids) {
        if (!indexed.contains(book_id)) {
            continue;
        }
        if (const auto stored = metadata.find_by_id(book_id)) {
            index.add_book(book_id, stage1::tokenize(stage1::read_text_file(stored->body_path), stopwords));
        }
    }

    for (int step = 0; step < steps; ++step) {
        const auto decision =
            stage1::run_pipeline_step(candidate_ids, downloaded, indexed, source, datalake, metadata, index,
                                       index_writer, stopwords);
        describe(decision);
        if (decision.action == stage1::ControlAction::Nothing) {
            break;  // dataset fully processed: no point looping further
        }
    }

    return 0;
}

// Runs one SPEC section 9 experiment against books a previous `pipeline <N>`
// run already downloaded (never the network: see load_sample_books), and
// writes the result CSV to benchmarks/results/cpp_<experiment>.csv, the same
// "results get committed, work is scratch" convention the Java module uses.
int run_benchmark(const std::string& experiment) {
    const auto stopwords = stage1::load_stopwords(kSharedDir / "stopwords.txt");
    const auto candidate_ids = stage1::load_book_ids(kSharedDir / "book_ids.txt");

    stage1::ControlLog downloaded(kDataDir / "control" / "downloaded_books.txt");
    stage1::MetadataStore metadata(kDataDir / "datamarts" / "metadata.db");
    const auto books = stage1::load_sample_books(candidate_ids, downloaded, metadata);

    if (books.empty()) {
        std::cerr << "[benchmark] no downloaded books found under " << kDataDir
                   << " -- run `pipeline <N>` first to populate some.\n";
        return 1;
    }
    std::cout << "[benchmark] using " << books.size() << " already-downloaded book(s)\n";

    const auto work_dir = kBenchmarksDir / "work";
    std::vector<stage1::BenchmarkResult> results;

    if (experiment == "datalake_write") {
        results = stage1::benchmark_datalake_write("cpp", books, work_dir);
    } else if (experiment == "datalake_lookup") {
        results = stage1::benchmark_datalake_lookup("cpp", books, work_dir);
    } else if (experiment == "datalake_incremental") {
        results = stage1::benchmark_datalake_incremental("cpp", books, work_dir);
    } else if (experiment == "datalake_recovery") {
        results = stage1::benchmark_datalake_recovery("cpp", books, work_dir);
    } else if (experiment == "datalake_storage") {
        results = stage1::benchmark_datalake_storage("cpp", books, work_dir);
    } else if (experiment == "index_build") {
        results = stage1::benchmark_index_build("cpp", books, stopwords, work_dir);
    } else if (experiment == "index_query") {
        // (Re)builds the structures first, untimed, so index_query always
        // measures against whatever `books` currently holds, regardless of
        // whether `index_build` happened to run earlier in this process.
        stage1::benchmark_index_build("cpp", books, stopwords, work_dir);
        const auto queries = stage1::load_queries(kSharedDir / "queries.txt");
        results = stage1::benchmark_index_query("cpp", static_cast<int>(books.size()), queries, stopwords, work_dir);
    } else {
        std::cerr << "[benchmark] unknown experiment: " << experiment << "\n";
        return 1;
    }

    const auto csv_path = kBenchmarksDir / "results" / ("cpp_" + experiment + ".csv");
    stage1::write_benchmark_results(csv_path, results);
    std::cout << "[benchmark] wrote " << results.size() << " rows to " << csv_path << "\n";
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    try {
        if (argc == 3 && std::string_view(argv[1]) == "pipeline") {
            char* end = nullptr;
            const long steps = std::strtol(argv[2], &end, 10);
            if (end == argv[2] || steps <= 0) {
                print_usage();
                return 1;
            }
            return run_pipeline(static_cast<int>(steps));
        }

        if (argc == 3 && std::string_view(argv[1]) == "benchmark") {
            return run_benchmark(argv[2]);
        }

        print_usage();
        return 1;
    } catch (const std::exception& error) {
        std::cerr << "[fatal] " << error.what() << "\n";
        return 1;
    }
}
