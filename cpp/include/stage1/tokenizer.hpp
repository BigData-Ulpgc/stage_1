#pragma once

#include <string>
#include <string_view>
#include <unordered_set>
#include <vector>

namespace stage1 {

// Splits `text` into tokens following section 5 of shared/SPEC.md:
//  - 'A'-'Z' are lowercased; 'a'-'z' and '0'-'9' belong to a token.
//  - Every other byte (spaces, punctuation, non-ASCII bytes) is a separator.
//  - Tokens shorter than 2 characters are dropped.
// Stopwords are not removed by this overload.
std::vector<std::string> tokenize(std::string_view text);

// Same as above, and also drops every token present in `stopwords`
// (compared after lowercasing, so the set must hold lowercase words).
std::vector<std::string> tokenize(std::string_view text, const std::unordered_set<std::string>& stopwords);

}  // namespace stage1
