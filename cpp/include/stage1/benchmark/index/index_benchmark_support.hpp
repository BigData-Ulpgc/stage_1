#pragma once

#include <functional>
#include <string>
#include <unordered_set>
#include <vector>

#include "stage1/benchmark/sample_books.hpp"
#include "stage1/datamart/index/inverted_index.hpp"

namespace stage1 {

// What the index experiments share, ported from the Java module's
// IndexBenchmark so that both languages run them under the same conditions.

// A book reduced to what an index stores of it: its id and its distinct terms
// (Java's TokenizedBook).
struct TokenizedBook {
    int book_id;
    std::vector<std::string> terms;  // distinct, sorted
};

// Tokenizes every book once, before any measurement (Java's tokenizeAll): the
// tokenizer is in no index timing, and every structure receives exactly the
// same terms.
std::vector<TokenizedBook> tokenize_all(const std::vector<SampleBook>& books,
                                        const std::unordered_set<std::string>& stopwords);

// The in-memory index of `books`, the one every writer of this module persists.
InvertedIndex build_index(const std::vector<TokenizedBook>& books);

// Java's IndexBenchmark.verify. `postings` (an opened on-disk structure) must
// match the in-memory index built from the same `books`, for:
//  - the postings of every query term, and of the first 20 terms (in sorted
//    order) of the first and of the last book;
//  - the answer of every query in `queries`.
// Throws std::runtime_error, naming `what`, at the first difference: a
// benchmark of a structure that gives wrong answers must fail, not report.
void verify_index(const std::function<std::vector<int>(const std::string&)>& postings,
                  const std::vector<TokenizedBook>& books, const std::vector<std::string>& queries,
                  const std::unordered_set<std::string>& stopwords, const std::string& what);

}  // namespace stage1
