#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "stage1/datamart/index/index_writer.hpp"

namespace stage1 {

// Folder name for `term`'s first character, uppercased (shared/SPEC.md section 6):
// e.g. "car" -> "C". Pure, no filesystem access. Assumes `term` is non-empty and
// contains only [a-z0-9] (a digit uppercases to itself), which is guaranteed by
// stage1::tokenize's output -- every term this writer ever sees comes from there.
std::string hierarchical_folder_name(const std::string& term);

// Hierarchical datamart layout for the index (shared/SPEC.md section 6):
//   <root>/<FIRST-LETTER>/<term>.txt, one book id per line.
//
// The `root` folder belongs to this writer: write() deletes whatever is in it
// before writing, so the folder ends up holding exactly the given index (a
// term that is no longer in the index loses its file). update_terms() only
// ever adds or rewrites the files of the terms it is given.
class HierarchicalIndexWriter : public IndexWriter {
public:
    explicit HierarchicalIndexWriter(std::filesystem::path root) : root_(std::move(root)) {}

    void write(const InvertedIndex& index) override;
    void update_terms(const InvertedIndex& index, const std::vector<std::string>& changed_terms) override;

private:
    std::filesystem::path root_;
};

}  // namespace stage1
