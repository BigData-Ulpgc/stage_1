#include "stage1/sample_books.hpp"

#include "stage1/file_io.hpp"

namespace stage1 {

std::vector<SampleBook> load_sample_books(const std::vector<int>& candidate_ids, const ControlLog& downloaded,
                                           const MetadataStore& metadata) {
    std::vector<SampleBook> books;
    for (int book_id : candidate_ids) {
        if (!downloaded.contains(book_id)) {
            continue;  // not downloaded yet: nothing to read
        }
        if (const auto stored = metadata.find_by_id(book_id)) {
            books.push_back(SampleBook{book_id, read_text_file(stored->body_path)});
        }
        // No metadata row despite being marked downloaded should not normally
        // happen (the pipeline always stores metadata before marking), so
        // silently skipping it here mirrors main.cpp's own index rebuild.
    }
    return books;
}

}  // namespace stage1
