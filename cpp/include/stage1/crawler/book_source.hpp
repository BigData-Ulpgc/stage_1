#pragma once

#include "stage1/crawler/download_result.hpp"

namespace stage1 {

// Abstract contract for "get the raw text of a book, given its id", regardless
// of which catalog or web service it actually comes from.
class BookSource {
public:
    virtual ~BookSource() = default;
    virtual DownloadResult fetch(int book_id) = 0;
};

}  // namespace stage1
