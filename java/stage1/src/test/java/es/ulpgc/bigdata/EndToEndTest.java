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
 * Prueba de sistema (reto 31): el programa entero, montado por SearchEngine igual que en Main,
 * con 3 libros controlados y SIN red (una BookSource falsa en lugar de Gutenberg).
 *
 *   1. pipeline hasta IDLE: descargar al datalake + indexar (metadatos + índice) + control
 *   2. comprobar metadatos y varias búsquedas AND
 *   3. cerrar, volver a abrir desde disco y repetir: mismos resultados, nada se descarga otra vez
 *
 * Se repite con varias combinaciones de datalake e índice; mongo sólo si está arrancado.
 */
class EndToEndTest {

    @TempDir Path tmp;

    // ------------------------------------------------------------------
    // Los libros: pequeños, conocidos, en tres rangos de 1000 distintos
    // ------------------------------------------------------------------

    private static String gutenberg(int id, String title, String author, String body) {
        return "The Project Gutenberg eBook of " + title + "\r\n\r\n"         // \r\n como los ficheros reales
                + "Title: " + title + "\r\n"
                + "Author: " + author + "\r\n"
                + "Release date: March 3, 2001 [eBook #" + id + "]\r\n"
                + "Language: English\r\n\r\n"
                + "*** START OF THE PROJECT GUTENBERG EBOOK " + title.toUpperCase() + " ***\r\n"
                + body + "\r\n"
                + "*** END OF THE PROJECT GUTENBERG EBOOK " + title.toUpperCase() + " ***\r\n"
                + "Please donate to keep the license alive.\r\n";              // footer: no debe indexarse
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

    /** Consulta -> resultado esperado. Cada una prueba algo distinto del camino completo. */
    private static final Map<String, List<Integer>> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("ship sea", List.of(10, 1500));            // AND de dos términos en dos libros
        EXPECTED.put("ship", List.of(10, 1500));
        EXPECTED.put("WHALE!", List.of(10));                     // mayúsculas y puntuación en consulta y libro
        EXPECTED.put("crew, treasure", List.of(1500));
        EXPECTED.put("love father 1813", List.of(2003));         // números también son términos
        EXPECTED.put("whale love", List.of());                   // cada uno existe, juntos no
        EXPECTED.put("the of", List.of());                       // sólo stopwords (shared/stopwords.txt)
        EXPECTED.put("letters", List.of());                      // sólo en el título: el header NO se indexa
        EXPECTED.put("testville", List.of());                    // sólo en el autor
        EXPECTED.put("donate", List.of());                       // sólo en el footer: se descarta
    }

    /** Gutenberg falso que cuenta cuántas veces se le pide un libro. */
    private static final class FakeGutenberg implements BookSource {
        final AtomicInteger fetches = new AtomicInteger();

        @Override
        public Optional<String> fetch(int bookId) {
            fetches.incrementAndGet();
            return Optional.ofNullable(BOOKS.get(bookId));
        }
    }

    /** Después del reinicio no se debe descargar nada: si se pide un libro, la prueba falla. */
    private static final BookSource MUST_NOT_FETCH = id -> {
        throw new AssertionError("tras el reinicio se volvió a descargar el libro " + id);
    };

    // ------------------------------------------------------------------
    // Configuración aislada: todo bajo una carpeta temporal
    // ------------------------------------------------------------------

    private AppConfig config(String datalake, String index) throws IOException {
        Path shared = tmp.resolve("shared");
        Files.createDirectories(shared);
        Files.writeString(shared.resolve("book_ids.txt"), "# dataset de la prueba\n10\n1500\n2003\n");
        Files.copy(AppConfig.defaults().stopwordsFile(), shared.resolve("stopwords.txt"));   // las stopwords del contrato

        Properties p = new Properties();
        p.setProperty(AppConfig.DATA_DIR, tmp.resolve("data").toString());
        p.setProperty(AppConfig.SHARED_DIR, shared.toString());
        p.setProperty(AppConfig.DATALAKE_STRUCTURE, datalake);
        p.setProperty(AppConfig.INDEX_STRUCTURE, index);
        p.setProperty(AppConfig.MONGO_DATABASE, "search_engine_test");            // nunca el índice real
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
    // La prueba
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

        // --- 1. Primera ejecución: datalake + índice + metadatos + control -------------------
        try (SearchEngine engine = SearchEngine.open(config, gutenberg)) {
            List<StepResult> steps = engine.pipeline().runUntilIdle(100);

            assertEquals(6, steps.size(), "3 descargas + 3 indexaciones: " + steps);
            assertTrue(steps.stream().allMatch(StepResult::marked), steps.toString());
            assertEquals(3, gutenberg.fetches.get());
            assertEquals(List.of(10, 1500, 2003), engine.datalake().listBookIds());

            // --- 2. Metadatos: parseados del header y enlazados con el datalake ---------------
            BookMetadata whale = engine.metadata().findById(10).orElseThrow();
            assertEquals("The Whale and the Sea", whale.title());
            assertEquals("Herman Testville", whale.author());
            assertEquals("English", whale.language());
            assertEquals("March 3, 2001", whale.releaseDate());                    // sin "[eBook #10]"
            BookLocation stored = engine.datalake().locate(10).orElseThrow();
            assertEquals(stored.bodyPath(), whale.bodyPath());
            assertEquals(stored.headerPath(), whale.headerPath());
            assertTrue(Files.readString(whale.bodyPath()).startsWith("Call me Tester."));
            assertFalse(Files.readString(whale.bodyPath()).contains("\r"));          // saltos normalizados
            assertEquals(List.of(2003), engine.metadata().findByAuthor("Mary Quill").stream()
                    .map(BookMetadata::bookId).toList());
            assertEquals(3, engine.metadata().count());

            // --- Búsquedas AND --------------------------------------------------------------------
            before = runAllQueries(engine);
            assertEquals(EXPECTED, before);
        }

        // --- Dónde está cada parte del estado (con todo cerrado) --------------------------------
        assertStateOnDisk(config);

        // --- 3. Reinicio: todo se vuelve a abrir desde disco -------------------------------------
        try (SearchEngine reopened = SearchEngine.open(config, MUST_NOT_FETCH)) {
            assertEquals(before, runAllQueries(reopened), "el resultado cambió tras el reinicio");
            assertEquals(List.of(), reopened.pipeline().runUntilIdle(100), "no debía quedar nada pendiente");
            assertEquals(Set.of(10, 1500, 2003), reopened.control().indexed());
            assertEquals("The Whale and the Sea", reopened.metadata().findById(10).orElseThrow().title());
            if (indexName.equals("mongo")) {
                reopened.index().clear();                                          // no dejar colecciones de prueba
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
        // Sin close(): como si el proceso muriera aquí. Lo que ya se marcó debe estar en disco.

        try (SearchEngine reopened = SearchEngine.open(config, MUST_NOT_FETCH)) {
            assertEquals(before, runAllQueries(reopened));
            assertEquals(List.of(), reopened.pipeline().runUntilIdle(100));
        } finally {
            crashed.close();
        }
    }

    // ------------------------------------------------------------------
    // Criterio: señalar qué archivo/base contiene cada parte del estado
    // ------------------------------------------------------------------

    private static void assertStateOnDisk(AppConfig c) throws IOException {
        // Datalake: header y body de cada libro, bajo <data>/datalake/<estructura>/
        try (var files = Files.walk(c.datalakeDir())) {
            List<String> names = new ArrayList<>(files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString()).toList());
            assertEquals(6, names.size(), "header + body de 3 libros: " + names);
        }
        switch (c.datalakeStructure()) {
            case "book" -> assertTrue(Files.exists(c.datalakeDir().resolve("10/body.txt")));
            case "range" -> assertTrue(Files.exists(c.datalakeDir().resolve("01000-01999/1500.body.txt")));
            default -> { }                                                        // time: depende de la hora
        }

        // Metadatos: SQLite
        assertTrue(Files.size(c.metadataDb()) > 0);

        // Índice invertido: su fichero o carpeta (Mongo vive en otro proceso)
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

        // Control: un id por línea
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
