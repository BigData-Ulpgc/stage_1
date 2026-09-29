#include "stage1/monolithic_index_writer.hpp"

#include <nlohmann/json.hpp>

#include "stage1/file_io.hpp"

namespace stage1 {

void MonolithicIndexWriter::write(const InvertedIndex& index) {
    // An explicit empty object, not an empty array: nlohmann::json would
    // otherwise default-construct `document` as null, and dump "null" for an
    // index with no terms instead of the "{}" the SPEC's format implies.
    nlohmann::json document = nlohmann::json::object();

    for (const auto& entry : index.entries()) {
        document[entry.term] = entry.postings;  // std::vector<int> -> JSON array
    }

    // Compact, not pretty-printed: the index can have hundreds of thousands of
    // terms, and the file is meant to be read by a program, not by a person.
    write_text_file(path_, document.dump());
}

}  // namespace stage1
