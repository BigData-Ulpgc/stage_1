#pragma once

#include <functional>
#include <string>
#include <vector>

#include "stage1/inverted_index.hpp"

namespace stage1 {

// Runs an AND query (shared/SPEC.md section 7): the result is the ascending,
// de-duplicated intersection of the postings of every distinct term in
// `terms`. `postings` answers "what are this term's postings" -- from
// wherever they come: an in-memory InvertedIndex, a file read straight off
// disk, a database query. Pass tokenize()'s output (with the same stopwords
// used when indexing) as `terms`, so the query is tokenized exactly like the
// books were.
//
// Empty if `terms` is empty (no meaningful terms means no results, not "every
// book") or if any term's postings come back empty.
std::vector<int> query_and(const std::function<std::vector<int>(const std::string&)>& postings,
                            const std::vector<std::string>& terms);

// Convenience overload for the common case: querying an in-memory InvertedIndex.
std::vector<int> query_and(const InvertedIndex& index, const std::vector<std::string>& terms);

}  // namespace stage1
