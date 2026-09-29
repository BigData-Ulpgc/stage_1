#pragma once

#include <string>
#include <vector>

#include "stage1/inverted_index.hpp"

namespace stage1 {

// Runs an AND query over `index` (shared/SPEC.md section 7): the result is the
// ascending, de-duplicated intersection of the postings of every distinct term
// in `terms`. Pass tokenize()'s output (with the same stopwords used when
// indexing) so the query is tokenized exactly like the books were.
//
// Empty if `terms` is empty (no meaningful terms means no results, not "every
// book") or if any term never appears in the index.
std::vector<int> query_and(const InvertedIndex& index, const std::vector<std::string>& terms);

}  // namespace stage1
