package es.ulpgc.bigdata.datamart.index;

import es.ulpgc.bigdata.datalake.BookBasedDatalake;
import es.ulpgc.bigdata.datalake.Datalake;
import es.ulpgc.bigdata.datamart.metadata.MetadataParser;
import es.ulpgc.bigdata.datamart.metadata.MetadataRepository;
import es.ulpgc.bigdata.datamart.metadata.MetadataRepositoryException;
import es.ulpgc.bigdata.datamart.metadata.SqliteMetadataRepository;
import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.BookMetadata;
import es.ulpgc.bigdata.model.RawBook;
import es.ulpgc.bigdata.query.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IndexerTest {

    private static final RawBook PRIDE = new RawBook(1342,
            """
            The Project Gutenberg eBook of Pride and Prejudice

            Title: Pride and Prejudice

            Author: Jane Austen

            Release date: June 1, 1998 [eBook #1342]

            Language: English""",
            "It is a truth universally acknowledged, that a single man in possession "
                    + "of a good fortune, must be in want of a wife.");

    @TempDir Path tmp;

    private Path dbFile;
    private Path indexFile;

    private Datalake datalake;
    private MetadataRepository metadata;
    private InvertedIndex index;
    private Tokenizer tokenizer;
    private Indexer indexer;

    @BeforeEach
    void setUp() {
        dbFile = tmp.resolve("datamarts/metadata.db");
        indexFile = tmp.resolve("datamarts/inverted_index.json");

        datalake = new BookBasedDatalake(tmp.resolve("datalake/book"));
        metadata = new SqliteMetadataRepository(dbFile);
        index = new MonolithicJsonIndex(indexFile);
        tokenizer = new Tokenizer(Set.of("the", "of", "and", "in", "is", "it", "that", "be"));
        indexer = new Indexer(datalake, new MetadataParser(), metadata, tokenizer, index);
    }

    @AfterEach
    void tearDown() {
        metadata.close();
        index.close();
    }

    // --- Challenge criteria --------------------------------------------------

    @Test
    void libroQueNoEstaEnElDatalakeNoCreaEstadoParcial() {
        assertTrue(indexer.index(1342).isEmpty());

        assertEquals(0, metadata.count());
        assertEquals(List.of(), index.postings("truth"));
        assertFalse(Files.exists(indexFile));               // the index has not even been written
    }

    @Test
    void despuesDeIndexarSeConsultanMetadatosYUnTerminoDelBody() {
        BookLocation location = datalake.save(PRIDE);

        BookMetadata saved = indexer.index(1342).orElseThrow();

        BookMetadata fromDb = metadata.findById(1342).orElseThrow();
        assertEquals(saved, fromDb);
        assertEquals("Pride and Prejudice", fromDb.title());
        assertEquals("Jane Austen", fromDb.author());
        assertEquals(location.headerPath(), fromDb.headerPath());   // the paths are saved too
        assertEquals(location.bodyPath(), fromDb.bodyPath());

        assertEquals(List.of(1342), index.postings("truth"));
        assertEquals(List.of(1342), index.postings("universally"));
    }

    // --- What gets indexed -----------------------------------------------------

    @Test
    void elIndiceSoloContieneTerminosDelBody() {
        datalake.save(PRIDE);
        indexer.index(1342);

        assertEquals(List.of(), index.postings("gutenberg"));      // only in the header
        assertEquals(List.of(), index.postings("austen"));         // only in the header
        assertEquals(List.of(), index.postings("the"));            // stopword
        assertEquals(List.of(1342), index.postings("wife"));
    }

    @Test
    void elBuscadorEncuentraElLibroIndexado() {
        datalake.save(PRIDE);
        datalake.save(new RawBook(84, "Title: Frankenstein\nAuthor: Mary Shelley",
                "You will rejoice to hear that no disaster has accompanied the commencement"));
        indexer.index(1342);
        indexer.index(84);

        SearchService search = new SearchService(tokenizer, index);

        assertEquals(List.of(1342), search.search("single man"));
        assertEquals(List.of(84), search.search("disaster"));
        assertEquals(List.of(), search.search("single disaster"));
    }

    // --- Persistence -----------------------------------------------------------

    @Test
    void todoSigueAhiTrasCerrarYReabrir() {
        datalake.save(PRIDE);
        indexer.index(1342);
        metadata.close();
        index.close();

        metadata = new SqliteMetadataRepository(dbFile);            // "another run"
        index = new MonolithicJsonIndex(indexFile);

        assertEquals("Jane Austen", metadata.findById(1342).orElseThrow().author());
        assertEquals(List.of(1342), index.postings("fortune"));
    }

    @Test
    void indexarDosVecesNoDuplicaNada() {
        datalake.save(PRIDE);
        indexer.index(1342);
        indexer.index(1342);

        assertEquals(1, metadata.count());
        assertEquals(List.of(1342), index.postings("truth"));
    }

    // --- Failures: what state is left -----------------------------------------

    @Test
    void siFallaSqliteElIndiceNoSeToca() {
        datalake.save(PRIDE);
        metadata.close();                                    // any save will fail

        assertThrows(MetadataRepositoryException.class, () -> indexer.index(1342));

        assertEquals(List.of(), index.postings("truth"));
        assertFalse(Files.exists(indexFile));
        metadata = new SqliteMetadataRepository(dbFile);     // so that tearDown can close it
    }

    @Test
    void siFallaElIndiceQuedanMetadatosYReindexarLoArregla() {
        datalake.save(PRIDE);
        FailingFlushIndex failing = new FailingFlushIndex(index);
        Indexer withFailingIndex = new Indexer(datalake, new MetadataParser(), metadata, tokenizer, failing);

        assertThrows(UncheckedIOException.class, () -> withFailingIndex.index(1342));

        // Partial state: the metadata is there, the on-disk index is not.
        assertTrue(metadata.findById(1342).isPresent());
        assertFalse(Files.exists(indexFile));

        // Recovery: the book was not marked as indexed, so it is indexed again.
        failing.failNextFlush = false;
        withFailingIndex.index(1342);

        assertEquals(1, metadata.count());
        assertTrue(Files.exists(indexFile));
        assertEquals(List.of(1342), new MonolithicJsonIndex(indexFile).postings("truth"));
    }

    @Test
    void siElBodyDesapareceNoSeEscribeNada() throws Exception {
        BookLocation location = datalake.save(PRIDE);
        Datalake staleDatalake = new Datalake() {           // locate says it is there, but the body is gone
            @Override public String name() { return "stale"; }
            @Override public BookLocation save(RawBook book) { throw new UnsupportedOperationException(); }
            @Override public java.util.Optional<BookLocation> locate(int id) { return java.util.Optional.of(location); }
            @Override public List<Integer> listBookIds() { return List.of(1342); }
        };
        Files.delete(location.bodyPath());
        Indexer staleIndexer = new Indexer(staleDatalake, new MetadataParser(), metadata, tokenizer, index);

        assertThrows(UncheckedIOException.class, () -> staleIndexer.index(1342));

        assertEquals(0, metadata.count());                   // everything was read BEFORE writing
        assertFalse(Files.exists(indexFile));
    }

    /** Index that delegates to another one, but whose flush fails while failNextFlush is true. */
    private static final class FailingFlushIndex implements InvertedIndex {
        private final InvertedIndex delegate;
        boolean failNextFlush = true;

        FailingFlushIndex(InvertedIndex delegate) { this.delegate = delegate; }

        @Override public void flush() {
            if (failNextFlush) {
                throw new UncheckedIOException(new java.io.IOException("disco lleno (simulado)"));
            }
            delegate.flush();
        }

        @Override public String name() { return delegate.name(); }
        @Override public void addDocument(int bookId, Set<String> terms) { delegate.addDocument(bookId, terms); }
        @Override public List<Integer> postings(String term) { return delegate.postings(term); }
        @Override public void clear() { delegate.clear(); }
        @Override public long diskUsageBytes() { return delegate.diskUsageBytes(); }
        @Override public void close() { delegate.close(); }
    }
}