package es.ulpgc.bigdata.crawler;

import es.ulpgc.bigdata.datalake.Datalake;
import es.ulpgc.bigdata.model.BookLocation;

import java.util.Objects;
import java.util.Optional;

/**
 * First composite component: downloads a book, splits it and saves it.
 *
 *   fetch  -> if there is text,  split
 *   split  -> if it is valid,    save
 *   save   -> BookLocation
 *
 * It does nothing by itself: it coordinates three pieces it receives through the constructor.
 * It does not touch control files either; that is the responsibility of challenge 24.
 */
public class BookDownloader {

    private final BookSource source;
    private final BookSplitter splitter;
    private final Datalake datalake;

    public BookDownloader(BookSource source, BookSplitter splitter, Datalake datalake) {
        this.source = Objects.requireNonNull(source, "source");
        this.splitter = Objects.requireNonNull(splitter, "splitter");
        this.datalake = Objects.requireNonNull(datalake, "datalake");
    }

    /**
     * @return where the book was saved, or empty if it was not available
     *         or its text did not have the Gutenberg markers. In both cases
     *         nothing is written to the datalake.
     * @throws RuntimeException if the network or the disk fails: the book is not
     *         saved and the caller decides whether to retry.
     */
    public Optional<BookLocation> download(int bookId) {
        if (bookId < 0) {
            throw new IllegalArgumentException("book_id negativo: " + bookId);
        }
        return source.fetch(bookId)                        // Optional<String>
                .flatMap(text -> splitter.split(bookId, text))  // Optional<RawBook>
                .map(datalake::save);                      // Optional<BookLocation>
    }
}