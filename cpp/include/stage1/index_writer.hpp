#pragma once

#include "stage1/inverted_index.hpp"

namespace stage1 {

// Abstract contract for "persist an inverted index to some on-disk (or remote)
// structure" (shared/SPEC.md section 6). Each concrete writer picks its own
// physical layout (a single file, many small files, a database, ...); the
// project benchmarks these layouts against each other, so this interface is
// what different benchmark runs plug into, mirroring Datalake for section 3.
class IndexWriter {
public:
    virtual ~IndexWriter() = default;
    virtual void write(const InvertedIndex& index) = 0;
};

}  // namespace stage1
