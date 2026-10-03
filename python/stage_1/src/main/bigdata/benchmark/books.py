"""Synthetic book generators for benchmarks.

Books are generated deterministically from a seed so benchmarks are reproducible.
Network downloads must be done BEFORE any timing.
"""
from __future__ import annotations
import random
import bisect
from pathlib import Path
from typing import List

from ..models import RawBook
from ..datalake.base import Datalake


def from_datalake(source: Datalake) -> List[RawBook]:
    """Read all books from an existing datalake, sorted by id."""
    books = []
    for book_id in source.list_book_ids():
        loc = source.locate(book_id)
        if loc is None:
            continue
        header = loc.header_path.read_text(encoding='utf-8')
        body = loc.body_path.read_text(encoding='utf-8')
        books.append(RawBook(id=book_id, header=header, body=body))
    return books


def synthetic(count: int, body_kilobytes: int = 300, seed: int = 1) -> List[RawBook]:
    """Generate synthetic books with random words."""
    rng = random.Random(seed)
    words = ['whale', 'sea', 'captain', 'ship', 'love', 'letter', 'house', 'war',
             'peace', 'night', 'garden', 'river', 'king', 'queen', 'truth', 'fortune']
    books = []
    book_id = 1
    for _ in range(count):
        book_id += 1 + rng.randint(0, 699)
        target_len = body_kilobytes * 1024
        body_parts = []
        length = 0
        while length < target_len:
            word = words[rng.randint(0, len(words) - 1)]
            body_parts.append(word)
            length += len(word) + 1
        body = ' '.join(body_parts)
        header = (f'Title: Synthetic Book {book_id}\n'
                  f'Author: Author {book_id % 37}\n'
                  f'Release date: January 1, 2000 [eBook #{book_id}]\n'
                  f'Language: English')
        books.append(RawBook(id=book_id, header=header, body=body))
    return books


_QUERY_WORDS = ['adventure', 'island', 'love', 'ship', 'sea', 'king',
                'queen', 'monster', 'creature', 'whale', 'detective',
                'crime', 'war', 'peace', 'mother', 'father']


def synthetic_zipf(
    count: int,
    tokens_per_book: int = 5000,
    vocabulary_size: int = 5000,
    seed: int = 1,
) -> List[RawBook]:
    """Generate books with Zipf-distributed vocabulary for realistic index benchmarks."""
    if vocabulary_size < 3000:
        raise ValueError('vocabulary_size must be >= 3000')
    
    # Build vocabulary: query words at specific ranks
    vocabulary = [None] * vocabulary_size
    for i, word in enumerate(_QUERY_WORDS):
        rank = 10 * (i + 1) * (i + 1) - 1
        if rank < vocabulary_size:
            vocabulary[rank] = word
    
    # Fill remaining with synthetic words
    next_word = 0
    for r in range(vocabulary_size):
        if vocabulary[r] is None:
            vocabulary[r] = 'x' + _base26(next_word)
            next_word += 1
    
    # Build cumulative Zipf distribution
    cumulative = []
    total = 0.0
    for r in range(vocabulary_size):
        total += 1.0 / (r + 1)
        cumulative.append(total)
    
    rng = random.Random(seed)
    books = []
    book_id = 1
    for _ in range(count):
        book_id += 1 + rng.randint(0, 699)
        body_parts = []
        for _ in range(tokens_per_book):
            x = rng.random() * total
            rank = bisect.bisect_left(cumulative, x)
            if rank >= vocabulary_size:
                rank = vocabulary_size - 1
            body_parts.append(vocabulary[rank])
        body = ' '.join(body_parts)
        header = (f'Title: Synthetic Book {book_id}\n'
                  f'Author: Author {book_id % 37}\n'
                  f'Release date: January 1, 2000 [eBook #{book_id}]\n'
                  f'Language: English')
        books.append(RawBook(id=book_id, header=header, body=body))
    return books


def _base26(n: int) -> str:
    """Convert integer to base-26 string (aa, ab, ..., ba, ...)."""
    chars = []
    while True:
        chars.append(chr(ord('a') + n % 26))
        n //= 26
        if n == 0:
            break
    while len(chars) < 2:
        chars.append('a')
    return ''.join(reversed(chars))
