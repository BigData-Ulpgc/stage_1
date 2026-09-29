#include "stage1/query_engine.hpp"

#include <algorithm>
#include <iterator>
#include <unordered_set>

namespace stage1 {

std::vector<int> query_and(const InvertedIndex& index, const std::vector<std::string>& terms) {
    // De-duplicate query terms first: "car car" must behave exactly like
    // "car" -- intersecting the same postings list with itself twice would
    // just redo the same work for the same result.
    const std::unordered_set<std::string> distinct_terms(terms.begin(), terms.end());
    if (distinct_terms.empty()) {
        return {};  // no meaningful terms -> no results, not "every book"
    }

    // Fetch every term's postings once, then process the smallest lists
    // first: as soon as the running intersection is empty, the whole AND is
    // empty and there is no point intersecting the remaining, possibly much
    // larger, lists at all.
    std::vector<std::vector<int>> postings_lists;
    postings_lists.reserve(distinct_terms.size());
    for (const auto& term : distinct_terms) {
        postings_lists.push_back(index.postings(term));
    }
    std::sort(postings_lists.begin(), postings_lists.end(),
              [](const auto& a, const auto& b) { return a.size() < b.size(); });

    std::vector<int> result = postings_lists.front();
    for (std::size_t i = 1; i < postings_lists.size() && !result.empty(); ++i) {
        std::vector<int> intersected;
        std::set_intersection(result.begin(), result.end(), postings_lists[i].begin(), postings_lists[i].end(),
                               std::back_inserter(intersected));
        result = std::move(intersected);
    }
    return result;
}

}  // namespace stage1
