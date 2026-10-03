#include "stage1/benchmark/index_benchmark_support.hpp"

#include <algorithm>
#include <cstddef>
#include <set>
#include <stdexcept>

#include "stage1/datamart/index/tokenizer.hpp"
#include "stage1/query/query_engine.hpp"

namespace stage1 {

std::vector<TokenizedBook> tokenize_all(const std::vector<SampleBook>& books,
                                        const std::unordered_set<std::string>& stopwords) {
    std::vector<TokenizedBook> tokenized;
    tokenized.reserve(books.size());
    for (const auto& book : books) {
        auto terms = tokenize(book.body, stopwords);
        std::sort(terms.begin(), terms.end());
        terms.erase(std::unique(terms.begin(), terms.end()), terms.end());
        tokenized.push_back(TokenizedBook{book.book_id, std::move(terms)});
    }
    return tokenized;
}

InvertedIndex build_index(const std::vector<TokenizedBook>& books) {
    InvertedIndex index;
    for (const auto& book : books) {
        index.add_book(book.book_id, book.terms);
    }
    return index;
}

void verify_index(const std::function<std::vector<int>(const std::string&)>& postings,
                  const std::vector<TokenizedBook>& books, const std::vector<std::string>& queries,
                  const std::unordered_set<std::string>& stopwords, const std::string& what) {
    if (books.empty()) {
        return;
    }
    const InvertedIndex reference = build_index(books);

    std::set<std::string> terms;
    for (const auto& query_text : queries) {
        const auto query_terms = tokenize(query_text, stopwords);
        terms.insert(query_terms.begin(), query_terms.end());
    }
    for (const TokenizedBook* book : {&books.front(), &books.back()}) {
        const std::size_t first_20 = std::min<std::size_t>(20, book->terms.size());  // terms are sorted
        terms.insert(book->terms.begin(), book->terms.begin() + static_cast<std::ptrdiff_t>(first_20));
    }
    for (const auto& term : terms) {
        if (postings(term) != reference.postings(term)) {
            throw std::runtime_error(what + ": different postings for \"" + term + "\"");
        }
    }
    for (const auto& query_text : queries) {
        const auto query_terms = tokenize(query_text, stopwords);
        if (query_and(postings, query_terms) != query_and(reference, query_terms)) {
            throw std::runtime_error(what + ": different result for \"" + query_text + "\"");
        }
    }
}

}  // namespace stage1
