#pragma once

#include <string>
#include <vector>

#include "stage1/datamart/index/inverted_index.hpp"

namespace stage1 {

// Abstract contract for "persist an inverted index to some on-disk (or remote)
// structure" (shared/SPEC.md section 6). Each concrete writer picks its own
// physical layout (a single file, many small files, a database, ...); the
// project benchmarks these layouts against each other, so this interface is
// what different benchmark runs plug into, mirroring Datalake for section 3.
class IndexWriter {
public:
    virtual ~IndexWriter() = default;

    // Makes the whole persisted structure match `index`: every term gets
    // (re)written. Correct for any layout, but its cost is proportional to
    // the *entire* index, not to how much actually changed.
    virtual void write(const InvertedIndex& index) = 0;

    // Persists only `changed_terms`' current postings from `index`, leaving
    // every other already-persisted term alone. `changed_terms` must list
    // every term whose postings changed (e.g. every distinct term of a newly
    // added book) -- anything left out silently stays stale.
    //
    // The default implementation just calls write(index): always correct,
    // but only as cheap as a full rewrite. A writer whose physical layout
    // can update a subset of terms on its own (hierarchical: rewrite only
    // those terms' files; mongo: upsert only those documents) overrides this
    // to actually be cheaper -- see DEVLOG entry 40 for why this exists and
    // what it measurably changed. A layout that fundamentally cannot update
    // part of itself (monolithic: one JSON file) has nothing to override.
    virtual void update_terms(const InvertedIndex& index, const std::vector<std::string>& changed_terms) {
        (void)changed_terms;
        write(index);
    }
};

}  // namespace stage1
