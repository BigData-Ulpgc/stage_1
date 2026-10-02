package es.ulpgc.bigdata.datamart.index;

import es.ulpgc.bigdata.datalake.Datalake;
import es.ulpgc.bigdata.datamart.metadata.MetadataParser;
import es.ulpgc.bigdata.datamart.metadata.MetadataRepository;
import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.BookMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bridge datalake -> datamarts:
 *
 *   header.txt --(MetadataParser)--> MetadataRepository (SQLite)
 *   body.txt   --(Tokenizer)-------> InvertedIndex (JSON, folders, Mongo...)
 *
 * Order of work:
 *  1. Read and compute EVERYTHING (header, body, metadata, terms) without writing anything.
 *     If something fails here, no half-done state is left.
 *  2. Save metadata.
 *  3. Add to the index and flush.
 * Only when it finishes successfully can the caller mark the book as indexed (challenge 24).
 *
 * Indexing the same book twice is safe: save is an upsert and addDocument does not duplicate ids.
 */
public class Indexer {

    private final Datalake datalake;
    private final MetadataParser parser;
    private final MetadataRepository metadata;
    private final Tokenizer tokenizer;
    private final InvertedIndex index;

    public Indexer(Datalake datalake, MetadataParser parser, MetadataRepository metadata,
                   Tokenizer tokenizer, InvertedIndex index) {
        this.datalake = Objects.requireNonNull(datalake, "datalake");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.index = Objects.requireNonNull(index, "index");
    }

    /**
     * Indexes a book that is already in the datalake.
     *
     * @return the saved metadata, or empty if the book is not in the datalake
     *         (in that case neither SQLite nor the index is touched).
     * @throws RuntimeException if reading, SQLite or the index fails. The book must NOT
     *         be marked as indexed; reindexing it later is safe.
     */
    public Optional<BookMetadata> index(int bookId) {
        Optional<BookLocation> found = datalake.locate(bookId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        BookLocation location = found.get();

        // 1. Read and compute. Nothing here writes.
        String header = read(location.headerPath());
        String body = read(location.bodyPath());
        BookMetadata bookMetadata = parser.parse(location, header);   // includes the paths
        Set<String> terms = tokenizer.uniqueTerms(body);               // only the body

        // 2. Metadata first: a book with metadata but no index is invisible
        //    to search; the opposite would give results with no title or author.
        metadata.save(bookMetadata);

        // 3. Index afterwards, and persisted before returning.
        index.addDocument(bookId, terms);
        index.flush();

        return Optional.of(bookMetadata);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + file, e);
        }
    }
}