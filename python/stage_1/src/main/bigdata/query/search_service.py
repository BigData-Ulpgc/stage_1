"""AND search service — intersects posting lists from an inverted index."""
from __future__ import annotations
from typing import List

from ..datamart.index.base import InvertedIndex
from ..datamart.index.tokenizer import tokenize


def search(index: InvertedIndex, query: str) -> List[int]:
    """Return sorted book IDs containing ALL query terms (AND semantics).
    
    Returns empty list if the query has no valid terms.
    """
    terms = tokenize(query)
    if not terms:
        return []
    
    # Get posting lists; short-circuit if any term has no postings
    posting_lists = []
    for term in terms:
        postings = index.postings(term)
        if not postings:
            return []
        posting_lists.append(postings)
    
    # Sort by size: intersect smallest first
    posting_lists.sort(key=len)
    
    # Chain intersections
    result = posting_lists[0]
    for i in range(1, len(posting_lists)):
        result = _intersect_sorted(result, posting_lists[i])
        if not result:
            return []
    return result


def _intersect_sorted(a: List[int], b: List[int]) -> List[int]:
    """Intersect two sorted lists using two-pointer technique."""
    result = []
    i = j = 0
    while i < len(a) and j < len(b):
        if a[i] == b[j]:
            result.append(a[i])
            i += 1
            j += 1
        elif a[i] < b[j]:
            i += 1
        else:
            j += 1
    return result
