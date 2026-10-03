#include "stage1/benchmark/sample_books.hpp"

#include <algorithm>

#include "stage1/util/file_io.hpp"

namespace stage1 {

std::vector<SampleBook> load_sample_books(const std::vector<int>& candidate_ids, const ControlLog& downloaded,
                                           const MetadataStore& metadata) {
    std::vector<SampleBook> books;
    for (int book_id : candidate_ids) {
        if (!downloaded.contains(book_id)) {
            continue;  // not downloaded yet: nothing to read
        }
        if (const auto stored = metadata.find_by_id(book_id)) {
            books.push_back(
                SampleBook{book_id, read_text_file(stored->body_path), read_text_file(stored->header_path)});
        }
        // No metadata row despite being marked downloaded should not normally
        // happen (the pipeline always stores metadata before marking), so
        // silently skipping it here mirrors main.cpp's own index rebuild.
    }
    // SPEC section 10.1: benchmarks take the books in ascending id order, so
    // that a size N means "the N lowest ids" in every language.
    std::sort(books.begin(), books.end(),
              [](const SampleBook& a, const SampleBook& b) { return a.book_id < b.book_id; });
    return books;
}

}  // namespace stage1
