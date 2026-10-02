#pragma once

#include <filesystem>

#include "stage1/datamart/index/index_writer.hpp"

namespace stage1 {

// Writes the whole index to a single JSON file (shared/SPEC.md section 6):
//   datamarts/inverted_index.json -> {"term": [id1, id2, ...], ...}
class MonolithicIndexWriter : public IndexWriter {
public:
    explicit MonolithicIndexWriter(std::filesystem::path path) : path_(std::move(path)) {}

    void write(const InvertedIndex& index) override;

private:
    std::filesystem::path path_;
};

}  // namespace stage1
