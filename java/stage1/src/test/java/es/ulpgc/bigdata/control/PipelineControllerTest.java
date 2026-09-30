package es.ulpgc.bigdata.control;

import es.ulpgc.bigdata.control.StepResult.Action;
import es.ulpgc.bigdata.crawler.BookDownloader;
import es.ulpgc.bigdata.crawler.BookSource;
import es.ulpgc.bigdata.crawler.BookSplitter;
import es.ulpgc.bigdata.datalake.BookBasedDatalake;
import es.ulpgc.bigdata.datalake.Datalake;
import es.ulpgc.bigdata.datamart.index.InMemoryInvertedIndex;
import es.ulpgc.bigdata.datamart.index.Indexer;
import es.ulpgc.bigdata.datamart.index.InvertedIndex;
import es.ulpgc.bigdata.datamart.index.Tokenizer;
import es.ulpgc.bigdata.datamart.metadata.MetadataParser;
import es.ulpgc.bigdata.datamart.metadata.MetadataRepository;
import es.ulpgc.bigdata.datamart.metadata.SqliteMetadataRepository;
import es.ulpgc.bigdata.query.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pipeline completo con piezas reales (datalake, SQLite, índice, Indexer);
 * sólo la red es falsa. Gutenberg "tiene" los libros de FAKE_GUTENBERG.
 */
class PipelineControllerTest {

    private static final List<Integer> DATASET = List.of(1342, 84, 11);

    private static String gutenbergText(String title, String body) {
        return "Title: " + title + "\nAuthor: Someone\nLanguage: English\n\n"
                + "*** START OF THE PROJECT GUTENBERG EBOOK " + title.toUpperCase() + " ***\n"
                + body + "\n"
                + "*** END OF THE PROJECT GUTENBERG EBOOK " + title.toUpperCase() + " ***\n";
    }

    private static final Map<Integer, String> FAKE_GUTENBERG = Map.of(
            1342, gutenbergText("Pride and Prejudice", "a single man in possession of a good fortune"),
            84, gutenbergText("Frankenstein", "you will rejoice to hear that no disaster"),
            11, gutenbergText("Alice in Wonderland", "alice was beginning to get very tired"));

    @TempDir Path tmp;

    private Datalake datalake;
    private MetadataRepository metadata;
    private CountingIndex index;
    private Tokenizer tokenizer;

    /** Ids que se pidieron a "Gutenberg", en orden. */
    private final List<Integer> downloadRequests = new ArrayList<>();
    /** Ids para los que la red "falla". */
    private final Set<Integer> networkFailsFor = new HashSet<>();

    private final BookSource fakeGutenberg = id -> {
        downloadRequests.add(id);
        if (networkFailsFor.contains(id)) {
            throw new UncheckedIOException(new ConnectException("sin conexión (simulado)"));
        }
        return Optional.ofNullable(FAKE_GUTENBERG.get(id));   // vacío = 404
    };

    @BeforeEach
    void setUp() {
        datalake = new BookBasedDatalake(tmp.resolve("datalake/book"));
        metadata = new SqliteMetadataRepository(tmp.resolve("datamarts/metadata.db"));
        index = new CountingIndex(new InMemoryInvertedIndex());
        tokenizer = new Tokenizer(Set.of("the", "of", "a", "to", "in"));
    }

    @AfterEach
    void tearDown() {
        metadata.close();
    }

    /** Un controlador nuevo sobre los MISMOS ficheros: como arrancar el programa otra vez. */
    private PipelineController newController(List<Integer> dataset) {
        return new PipelineController(
                new ControlFiles(tmp.resolve("control")),
                new BookDownloader(fakeGutenberg, new BookSplitter(), datalake),
                new Indexer(datalake, new MetadataParser(), metadata, tokenizer, index),
                dataset);
    }

    private PipelineController newController() {
        return newController(DATASET);
    }

    private ControlFiles control() {
        return new ControlFiles(tmp.resolve("control"));      // relee los ficheros de disco
    }

    // --- Criterios del reto --------------------------------------------------

    @Test
    void cadaStepHaceComoMaximoUnaOperacion() {
        PipelineController pipeline = newController();

        for (int i = 0; i < 8; i++) {
            int opsBefore = downloadRequests.size() + index.totalAdds();
            pipeline.step();
            int opsAfter = downloadRequests.size() + index.totalAdds();
            assertTrue(opsAfter - opsBefore <= 1, "el paso " + i + " hizo más de una operación");
        }
    }

    @Test
    void secuenciaDescargarIndexarAlternada() {
        PipelineController pipeline = newController();

        List<StepResult> results = pipeline.runUntilIdle(100);

        assertEquals(List.of(
                StepResult.of(Action.DOWNLOADED, 1342),
                StepResult.of(Action.INDEXED, 1342),
                StepResult.of(Action.DOWNLOADED, 84),
                StepResult.of(Action.INDEXED, 84),
                StepResult.of(Action.DOWNLOADED, 11),
                StepResult.of(Action.INDEXED, 11)), results);
        assertEquals(Action.IDLE, pipeline.step().action());
    }

    @Test
    void nuncaSeIndexaDosVecesNiAunqueSeReinicie() {
        newController().runUntilIdle(100);
        newController().runUntilIdle(100);                     // segunda "ejecución"
        newController().runUntilIdle(100);                     // y tercera

        assertEquals(Map.of(1342, 1, 84, 1, 11, 1), index.addsPerBook());
        assertEquals(DATASET.size(), downloadRequests.size()); // tampoco se descarga dos veces
    }

    @Test
    void unaDescargaFallidaNoSeMarcaYSeReintentaAlReiniciar() {
        networkFailsFor.add(84);

        List<StepResult> results = newController().runUntilIdle(100);

        assertTrue(results.contains(new StepResult(Action.DOWNLOAD_FAILED, 84,
                "UncheckedIOException: java.net.ConnectException: sin conexión (simulado)")));
        assertFalse(control().isDownloaded(84));
        assertTrue(datalake.locate(84).isEmpty());
        assertTrue(control().isIndexed(11), "un fallo no debe parar el resto del dataset");

        networkFailsFor.clear();                                // "vuelve la red"
        List<StepResult> retry = newController().runUntilIdle(100);

        assertEquals(List.of(StepResult.of(Action.DOWNLOADED, 84), StepResult.of(Action.INDEXED, 84)), retry);
    }

    // --- Libros que no existen o no se pueden indexar ------------------------------

    @Test
    void libroQueGutenbergNoTieneNoSeMarcaYNoBloquea() {
        List<StepResult> results = newController(List.of(999, 84)).runUntilIdle(100);

        assertEquals(StepResult.of(Action.NOT_AVAILABLE, 999), results.get(0));
        assertEquals(StepResult.of(Action.INDEXED, 84), results.get(results.size() - 1));
        assertFalse(control().isDownloaded(999));
    }

    @Test
    void unaIndexacionFallidaNoSeMarcaYQuedaPendiente() {
        index.failFlush = true;

        List<StepResult> results = newController(List.of(1342)).runUntilIdle(100);

        assertEquals(Action.DOWNLOADED, results.get(0).action());
        assertEquals(Action.INDEX_FAILED, results.get(1).action());
        assertEquals(2, results.size(), "no debe reintentar en bucle en la misma ejecución");
        assertEquals(List.of(1342), control().readyToIndex());

        index.failFlush = false;
        assertEquals(List.of(StepResult.of(Action.INDEXED, 1342)), newController(List.of(1342)).runUntilIdle(100));
        assertEquals(List.of(), control().readyToIndex());
    }

    @Test
    void descargadoSegunElControlPeroAusenteDelDatalakeNoSeMarcaIndexado() {
        control().markDownloaded(555);                          // el control dice algo que no es verdad

        StepResult result = newController(List.of()).step();

        assertEquals(StepResult.of(Action.MISSING_FROM_DATALAKE, 555), result);
        assertFalse(control().isIndexed(555));
    }

    // --- Reanudar ------------------------------------------------------------------------

    @Test
    void alReiniciarContinuaDondeSeQuedo() {
        PipelineController first = newController();
        first.step();                                            // descarga 1342
        // El programa "se cierra" aquí, antes de indexar.

        PipelineController second = newController();             // lee el estado de los ficheros

        assertEquals(StepResult.of(Action.INDEXED, 1342), second.step());
        assertEquals(StepResult.of(Action.DOWNLOADED, 84), second.step());
        assertEquals(List.of(1342), downloadRequests.subList(0, 1));
        assertEquals(1, downloadRequests.stream().filter(id -> id == 1342).count());
    }

    @Test
    void indexarPendientesTienePrioridadSobreDescargar() throws IOException {
        // Situación de partida: dos libros ya descargados (por ejemplo, de una ejecución que se cortó).
        PipelineController setup = newController();
        setup.step();                                            // descarga 1342
        control().markDownloaded(84);
        datalake.save(new BookSplitter().split(84, FAKE_GUTENBERG.get(84)).orElseThrow());

        PipelineController pipeline = newController();

        assertEquals(StepResult.of(Action.INDEXED, 84), pipeline.step());     // readyToIndex va ordenado
        assertEquals(StepResult.of(Action.INDEXED, 1342), pipeline.step());
        assertEquals(StepResult.of(Action.DOWNLOADED, 11), pipeline.step());
    }

    @Test
    void runUntilIdleRespetaElMaximoDePasos() {
        // Descargar 1342, indexar 1342, descargar 84... y se para: el 84 queda sin indexar.
        assertEquals(3, newController().runUntilIdle(3).size());

        // Aunque el dataset esté vacío, lo pendiente se termina y luego no hay nada más.
        assertEquals(List.of(StepResult.of(Action.INDEXED, 84)), newController(List.of()).runUntilIdle(10));
        assertEquals(List.of(), newController(List.of()).runUntilIdle(10));
    }

    // --- De principio a fin ---------------------------------------------------------------

    @Test
    void trasElPipelineElBuscadorEncuentraLosLibros() {
        newController().runUntilIdle(100);

        SearchService search = new SearchService(tokenizer, index);
        assertEquals(List.of(1342), search.search("good fortune"));
        assertEquals(List.of(11), search.search("alice"));
        assertEquals("Frankenstein", metadata.findById(84).orElseThrow().title());
        assertTrue(Files.exists(tmp.resolve("control/indexed_books.txt")));
    }

    // --- Índice que cuenta cuántas veces se indexa cada libro -----------------------------

    private static final class CountingIndex implements InvertedIndex {
        private final InvertedIndex delegate;
        private final Map<Integer, Integer> adds = new HashMap<>();
        boolean failFlush = false;

        CountingIndex(InvertedIndex delegate) { this.delegate = delegate; }

        Map<Integer, Integer> addsPerBook() { return Map.copyOf(adds); }
        int totalAdds() { return adds.values().stream().mapToInt(Integer::intValue).sum(); }

        @Override public void addDocument(int bookId, Set<String> terms) {
            adds.merge(bookId, 1, Integer::sum);
            delegate.addDocument(bookId, terms);
        }
        @Override public void flush() {
            if (failFlush) {
                throw new UncheckedIOException(new IOException("disco lleno (simulado)"));
            }
            delegate.flush();
        }
        @Override public String name() { return delegate.name(); }
        @Override public List<Integer> postings(String term) { return delegate.postings(term); }
        @Override public void clear() { delegate.clear(); }
        @Override public long diskUsageBytes() { return delegate.diskUsageBytes(); }
        @Override public void close() { delegate.close(); }
    }
}