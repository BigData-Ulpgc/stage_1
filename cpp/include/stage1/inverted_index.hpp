#pragma once

#include <cstddef>
#include <set>
#include <string>
#include <unordered_map>
#include <vector>

namespace stage1 {

// One term and its ascending, de-duplicated postings list, as produced by
// InvertedIndex::entries(). Mirrors the {"term": "...", "postings": [...]}
// shape used by the on-disk formats (SPEC section 6).
struct IndexEntry {
    std::string term;
    std::vector<int> postings;
};

// An in-memory inverted index: term -> ascending, de-duplicated list of book ids
// that contain it (shared/SPEC.md section 6). Built incrementally, one book at a
// time; writing it to disk (monolithic/hierarchical/Mongo) is a later phase.
class InvertedIndex {
public:
    // Adds `book_id` to the postings of every distinct term in `tokens` (SPEC
    // section 5 point 6: a book contributes the *set* of its terms, not a bag
    // with repeats). `tokens` may contain repeats, as tokenize()'s output does;
    // this function de-duplicates them itself, so callers can pass tokenize()'s
    // result directly.
    void add_book(int book_id, const std::vector<std::string>& tokens);

    // Postings for `term`: ascending, without duplicates. Empty if `term` is not
    // in the index (not an error: an unknown word simply matches nothing).
    std::vector<int> postings(const std::string& term) const;

    // Number of distinct terms currently indexed.
    std::size_t term_count() const;

    // Every term currently indexed, each with its postings. Order is
    // unspecified. Meant for writers that persist the whole index to disk.
    std::vector<IndexEntry> entries() const;

private:
    std::unordered_map<std::string, std::set<int>> index_;
};

}  // namespace stage1
