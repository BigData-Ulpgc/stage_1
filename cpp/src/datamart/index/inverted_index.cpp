#include "stage1/datamart/index/inverted_index.hpp"

#include <unordered_set>

namespace stage1 {

void InvertedIndex::add_book(int book_id, const std::vector<std::string>& tokens) {
    std::unordered_set<std::string> seen;  // this book's distinct terms only
    seen.reserve(tokens.size());

    for (const auto& term : tokens) {
        if (seen.insert(term).second) {  // true only the first time we see `term`
            index_[term].insert(book_id);
        }
    }
}

std::vector<int> InvertedIndex::postings(const std::string& term) const {
    const auto it = index_.find(term);
    if (it == index_.end()) {
        return {};
    }
    return std::vector<int>(it->second.begin(), it->second.end());
}

std::size_t InvertedIndex::term_count() const { return index_.size(); }

std::vector<IndexEntry> InvertedIndex::entries() const {
    std::vector<IndexEntry> result;
    result.reserve(index_.size());
    for (const auto& [term, term_postings] : index_) {
        result.push_back(IndexEntry{term, std::vector<int>(term_postings.begin(), term_postings.end())});
    }
    return result;
}

}  // namespace stage1
