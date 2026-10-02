package es.ulpgc.bigdata;

import es.ulpgc.bigdata.config.AppConfig;
import es.ulpgc.bigdata.control.ControlFiles;
import es.ulpgc.bigdata.control.StepResult;
import es.ulpgc.bigdata.crawler.BookSource;
import es.ulpgc.bigdata.datamart.index.InvertedIndex;
import es.ulpgc.bigdata.datamart.index.MongoInvertedIndex;
import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.BookMetadata;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * System test (challenge 31): the whole program, assembled by SearchEngine just like in Main,
 * with 3 controlled books and NO network (a fake BookSource instead of Gutenberg).
 *
 *   1. pipeline until IDLE: download to the datalake + index (metadata + index) + control
 *   2. check metadata and several AND searches
 *   3. close, reopen from disk and repeat: same results, nothing is downloaded again
 *
 * It is repeated with several combinations of datalake and index; mongo only if it is running.
 */
class EndToEndTest {

    @TempDir Path tmp;

    // ------------------------------------------------------------------
    // The books: small, known, in three different ranges of 1000
    // ------------------------------------------------------------------

    private static String gutenberg(int id, String title, String author, String body) {
        return "The Project Gutenberg eBook of " + title + "\r\n\r\n"         // \r\n like the real files
                + "Title: " + title + "\r\n"
                + "Author: " + author + "\r\n"
                + "Release date: March 3, 2001 [eBook #" + id + "]\r\n"
                + "Language: English\r\n\r\n"
                + "*** START OF THE PROJECT GUTENBERG EBOOK " + title.toUpperCase() + " ***\r\n"
                + body + "\r\n"
                + "*** END OF THE PROJECT GUTENBERG EBOOK " + title.toUpperCase() + " ***\r\n"
                + "Please donate to keep the license alive.\r\n";              // footer: must not be indexed
    }

    private static final Map<Integer, String> BOOKS = Map.of(
            10, gutenberg(10, "The Whale and the Sea", "Herman Testville",
                    "Call me Tester. The captain chased the white WHALE across the grey sea.\r\n"
                            + "The ship sailed on, and the crew sang."),
            1500, gutenberg(1500, "Island Adventure", "Ada Lovely",
                    "A small ship reached a lonely island. Adventure waited beyond the sea;\r\n"
                            + "the crew found treasure."),
            2003, gutenberg(2003, "Letters of Love", "Mary Quill",
                    "A love letter from a mother to a father, written in the spring of 1813."));

    /** Query -> expected result. Each one tests something different of the full path. */
    private static final Map<String, List<Integer>> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("ship sea", List.of(10, 1500));            // AND of two terms in two books
        EXPECTED.put("ship", List.of(10, 1500));
        EXPECTED.put("WHALE!", List.of(10));                     // uppercase and punctuation in query and book
        EXPECTED.put("crew, treasure", List.of(1500));
        EXPECTED.put("love father 1813", List.of(2003));         // numbers are terms too
        EXPECTED.put("whale love", List.of());                   // each one exists, together they do not
        EXPECTED.put("the of", List.of());                       // only stopwords (shared/stopwords.txt)
        EXPECTED.put("letters", List.of());                      // only in the title: the header is NOT indexed
        EXPECTED.put("testville", List.of());                    // only in the author
        EXPECTED.put("donate", List.of());                       // only in the footer: discarded
    }

    /** Fake Gutenberg that counts how many times a book is requested. */
    private static final class FakeGutenberg implements BookSource {
        final AtomicInteger fetches = new AtomicInteger();

        @Override
        public Optional<String> fetch(int bookId) {
            fetches.incrementAndGet();
            return Optional.ofNullable(BOOKS.get(bookId));
        }
    }

    /** After the restart nothing must be downloaded: if a book is requested, the test fails. */
    private static final BookSource MUST_NOT_FETCH = id -> {
        throw new AssertionError("tras el reinicio se volvió a descargar el libro " + id);
    };

    // ------------------------------------------------------------------
    // Isolated configuration: everything under a temporary folder
    // ------------------------------------------------------------------

    private AppConfig config(String datalake, String index) throws IOException {
        Path shared = tmp.resolve("shared");
        Files.createDirectories(shared);
        Files.writeString(shared.resolve("book_ids.txt"), "# dataset de la prueba\n10\n1500\n2003\n");
        Files.copy(AppConfig.defaults().stopwordsFile(), shared.resolve("stopwords.txt"));   // the contract stopwords

        Properties p = new Properties();
        p.setProperty(AppConfig.DATA_DIR, tmp.resolve("data").toString());
        p.setProperty(AppConfig.SHARED_DIR, shared.toString());
        p.setProperty(AppConfig.DATALAKE_STRUCTURE, datalake);
        p.setProperty(AppConfig.INDEX_STRUCTURE, index);
        p.setProperty(AppConfig.MONGO_DATABASE, "search_engine_test");            // never the real index
        p.setProperty(AppConfig.MONGO_COLLECTION, "e2e_" + System.nanoTime());
        return AppConfig.fromProperties(p);
    }

    private static Map<String, List<Integer>> runAllQueries(SearchEngine engine) {
        Map<String, List<Integer>> results = new LinkedHashMap<>();
        for (String q : EXPECTED.keySet()) {
            results.put(q, engine.search().search(q));
        }
        return results;
    }

    // ------------------------------------------------------------------
    // The test
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "datalake={0}, index={1}")
    @CsvSource({"time, monolithic", "book, hierarchical", "range, monolithic", "book, mongo"})
    void descargarIndexarBuscarReiniciarYBuscarOtraVez(String datalakeName, String indexName) throws IOException {
        AppConfig config = config(datalakeName, indexName);
        if (indexName.equals("mongo")) {
            assumeTrue(mongoAvailable(config), "MongoDB no está disponible: caso mongo saltado");
        }
        FakeGutenberg gutenberg = new FakeGutenberg();
        Map<String, List<Integer>> before;

        // --- 1. First run: datalake + index + metadata + control -----------------------------
        try (SearchEngine engine = SearchEngine.open(config, gutenberg)) {
            List<StepResult> steps = engine.pipeline().runUntilIdle(100);

            assertEquals(6, steps.size(), "3 descargas + 3 indexaciones: " + steps);
            assertTrue(steps.stream().allMatch(StepResult::marked), steps.toString());
            assertEquals(3, gutenberg.fetches.get());
            assertEquals(List.of(10, 1500, 2003), engine.datalake().listBookIds());

            // --- 2. Metadata: parsed from the header and linked to the datalake ---------------
            BookMetadata whale = engine.metadata().findById(10).orElseThrow();
            assertEquals("The Whale and the Sea", whale.title());
            assertEquals("Herman Testville", whale.author());
            assertEquals("English", whale.language());
            assertEquals("March 3, 2001", whale.releaseDate());                    // without "[eBook #10]"
            BookLocation stored = engine.datalake().locate(10).orElseThrow();
            assertEquals(stored.bodyPath(), whale.bodyPath());
            assertEquals(stored.headerPath(), whale.headerPath());
            assertTrue(Files.readString(whale.bodyPath()).startsWith("Call me Tester."));
            assertFalse(Files.readString(whale.bodyPath()).contains("\r"));          // normalised line breaks
            assertEquals(List.of(2003), engine.metadata().findByAuthor("Mary Quill").stream()
                    .map(BookMetadata::bookId).toList());
            assertEquals(3, engine.metadata().count());

            // --- AND searches ---------------------------------------------------------------------
            before = runAllQueries(engine);
            assertEquals(EXPECTED, before);
        }

        // --- Where each part of the state is (with everything closed) ---------------------------
        assertStateOnDisk(config);

        // --- 3. Restart: everything is reopened from disk ----------------------------------------
        try (SearchEngine reopened = SearchEngine.open(config, MUST_NOT_FETCH)) {
            assertEquals(before, runAllQueries(reopened), "el resultado cambió tras el reinicio");
            assertEquals(List.of(), reopened.pipeline().runUntilIdle(100), "no debía quedar nada pendiente");
            assertEquals(Set.of(10, 1500, 2003), reopened.control().indexed());
            assertEquals("The Whale and the Sea", reopened.metadata().findById(10).orElseThrow().title());
            if (indexName.equals("mongo")) {
                reopened.index().clear();                                          // do not leave test collections behind
            }
        }
    }

    @ParameterizedTest(name = "index={0}")
    @CsvSource({"monolithic", "hierarchical"})
    void elEstadoSobreviveAunqueElProcesoMueraSinCerrar(String indexName) throws IOException {
        AppConfig config = config("book", indexName);
        SearchEngine crashed = SearchEngine.open(config, new FakeGutenberg());
        crashed.pipeline().runUntilIdle(100);
        Map<String, List<Integer>> before = runAllQueries(crashed);
        // No close(): as if the process died here. What was already marked must be on disk.

        try (SearchEngine reopened = SearchEngine.open(config, MUST_NOT_FETCH)) {
            assertEquals(before, runAllQueries(reopened));
            assertEquals(List.of(), reopened.pipeline().runUntilIdle(100));
        } finally {
            crashed.close();
        }
    }

    // ------------------------------------------------------------------
    // Criterion: point out which file/database holds each part of the state
    // ------------------------------------------------------------------

    private static void assertStateOnDisk(AppConfig c) throws IOException {
        // Datalake: header and body of each book, under <data>/datalake/<structure>/
        try (var files = Files.walk(c.datalakeDir())) {
            List<String> names = new ArrayList<>(files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString()).toList());
            assertEquals(6, names.size(), "header + body de 3 libros: " + names);
        }
        switch (c.datalakeStructure()) {
            case "book" -> assertTrue(Files.exists(c.datalakeDir().resolve("10/body.txt")));
            case "range" -> assertTrue(Files.exists(c.datalakeDir().resolve("01000-01999/1500.body.txt")));
            default -> { }                                                        // time: depends on the hour
        }

        // Metadata: SQLite
        assertTrue(Files.size(c.metadataDb()) > 0);

        // Inverted index: its file or folder (Mongo lives in another process)
        switch (c.indexStructure()) {
            case "monolithic" -> {
                String json = Files.readString(c.monolithicIndexFile());
                assertTrue(json.contains("\"whale\":[10]"), json);
                assertFalse(Files.exists(c.hierarchicalIndexDir()));
            }
            case "hierarchical" -> {
                assertEquals("10\n1500\n", Files.readString(c.hierarchicalIndexDir().resolve("S/ship.txt")));
                assertFalse(Files.exists(c.monolithicIndexFile()));
            }
            default -> { }
        }

        // Control: one id per line
        assertEquals(Set.of(10, 1500, 2003), idsIn(c.controlDir().resolve(ControlFiles.DOWNLOADED_FILE)));
        assertEquals(Set.of(10, 1500, 2003), idsIn(c.controlDir().resolve(ControlFiles.INDEXED_FILE)));
    }

    private static Set<Integer> idsIn(Path file) throws IOException {
        Set<Integer> ids = new TreeSet<>();
        for (String line : Files.readAllLines(file)) {
            ids.add(Integer.parseInt(line.strip()));
        }
        return ids;
    }

    private static boolean mongoAvailable(AppConfig c) {
        try (InvertedIndex probe = new MongoInvertedIndex(c.mongoUri(), c.mongoDatabase(), c.mongoCollection())) {
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
