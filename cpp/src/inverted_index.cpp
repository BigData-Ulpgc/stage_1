#include "stage1/inverted_index.hpp"

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

}  // namespace stage1
