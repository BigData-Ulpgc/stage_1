#pragma once

#include <filesystem>

#include "stage1/crawler/book_source.hpp"

namespace stage1 {

// BookSource backed by a local folder of raw Project Gutenberg files, named
// exactly like the last part of the download URL: <dir>/pg<ID>.txt (see
// sample_dataset/raw/). The offline counterpart of GutenbergSource: it returns
// the same bytes the URL would serve, untouched, so everything downstream
// (split, datalake, metadata, index) runs exactly as it does online.
//
// A missing or unreadable file is a failed fetch, never an exception: the
// pipeline then leaves the book unmarked, as it does after a failed download.
class LocalFileSource : public BookSource {
public:
    explicit LocalFileSource(std::filesystem::path dir) : dir_(std::move(dir)) {}

    DownloadResult fetch(int book_id) override;

private:
    std::filesystem::path dir_;
};

}  // namespace stage1
