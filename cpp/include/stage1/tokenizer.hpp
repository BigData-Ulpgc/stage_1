#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace stage1 {

// Splits `text` into tokens following section 5 of shared/SPEC.md:
//  - 'A'-'Z' are lowercased; 'a'-'z' and '0'-'9' belong to a token.
//  - Every other byte (spaces, punctuation, non-ASCII bytes) is a separator.
//  - Tokens shorter than 2 characters are dropped.
// Stopword removal is not done here yet.
std::vector<std::string> tokenize(std::string_view text);

}  // namespace stage1
