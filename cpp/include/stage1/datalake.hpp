#pragma once

#include <optional>
#include <string>

namespace stage1 {

// Where a book's header and body ended up after being written to the datalake.
struct BookLocation {
    std::string body_path;
    std::string header_path;
};

// Abstract contract for "store a book's already-split header and body, addressable
// by id" (shared/SPEC.md section 3). Each concrete Datalake organizes files under
// its own directory layout (time-based, book-based, range-based, ...); the project
// benchmarks these layouts against each other, so the interface is what different
// benchmark runs plug into.
class Datalake {
public:
    virtual ~Datalake() = default;

    // Writes header and body for `book_id`, creating any directories needed, and
    // returns the paths they were written to.
    virtual BookLocation write(int book_id, const std::string& header, const std::string& body) = 0;

    // Finds where `book_id`'s header and body are, or nullopt if this Datalake
    // has no record of them. SPEC section 3's "lookup cost" experiment exists
    // precisely because this costs very differently across layouts: for a
    // layout whose path is a pure function of the id (book, range) this is a
    // cheap, direct computation; for one where the path also depends on *when*
    // the book was written (time), it can only succeed for ids this same
    // Datalake instance has already written, and needs it to have remembered
    // that internally -- there is no way to compute "what hour was this
    // written in" from the id alone.
    virtual std::optional<BookLocation> locate(int book_id) const = 0;
};

}  // namespace stage1
