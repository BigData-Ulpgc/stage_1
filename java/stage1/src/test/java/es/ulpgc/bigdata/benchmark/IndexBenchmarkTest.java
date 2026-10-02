package es.ulpgc.bigdata.benchmark;

import es.ulpgc.bigdata.benchmark.IndexBenchmark.Backend;
import es.ulpgc.bigdata.benchmark.IndexBenchmark.TokenizedBook;
import es.ulpgc.bigdata.datamart.index.InvertedIndex;
import es.ulpgc.bigdata.datamart.index.MonolithicJsonIndex;
import es.ulpgc.bigdata.datamart.index.Tokenizer;
import es.ulpgc.bigdata.model.RawBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class IndexBenchmarkTest {

    private static final Tokenizer TOKENIZER = new Tokenizer(Set.of("the", "and", "of"));
    private static final List<String> QUERIES = List.of("whale", "ship sea", "king queen", "war peace");

    /** 40 small books with Zipf vocabulary: fast, but with lists of different sizes. */
    private static final List<TokenizedBook> DATASET =
            IndexBenchmark.tokenizeAll(BenchmarkBooks.syntheticZipf(40, 600, 5000, 3), TOKENIZER);

    @TempDir Path tmp;

    private IndexBenchmark benchmark;

    @BeforeEach
    void setUp() {
        benchmark = new IndexBenchmark(new BenchmarkRunner(1, 3), tmp.resolve("work"),
                IndexBenchmark.fileBackends(), TOKENIZER, QUERIES, 2);
    }

    private static List<BenchmarkRow> rows(List<BenchmarkRow> rows, String structure, String metric) {
        return rows.stream().filter(r -> r.structure().equals(structure) && r.metric().equals(metric)).toList();
    }

    // --- The backends receive the same terms ---------------------------------------------

    @Test
    void tokenizarAntesDaLosMismosTerminosQueElTokenizer() {
        List<RawBook> raw = BenchmarkBooks.syntheticZipf(5, 300, 5000, 3);
        List<TokenizedBook> tokenized = IndexBenchmark.tokenizeAll(raw, TOKENIZER);

        for (int i = 0; i < raw.size(); i++) {
            assertEquals(raw.get(i).id(), tokenized.get(i).id());
            assertEquals(TOKENIZER.uniqueTerms(raw.get(i).body()), tokenized.get(i).terms());
        }
        assertThrows(UnsupportedOperationException.class, () -> tokenized.get(0).terms().add("hack"));
    }

    @Test
    void todosLosBackendsTienenElMismoIndiceLogico() {
        List<BenchmarkRow> disk = benchmark.disk(DATASET);     // verify would throw if a backend gave other postings

        for (String metric : List.of("terms", "postings")) {
            Set<Double> values = disk.stream().filter(r -> r.metric().equals(metric))
                    .map(BenchmarkRow::value).collect(Collectors.toSet());
            assertEquals(1, values.size(), metric + " distinto entre backends: " + values);
        }
    }

    @Test
    void unIndiceConOtrosTerminosNoPasaLaVerificacion() {
        InvertedIndex wrong = new MonolithicJsonIndex(tmp.resolve("wrong.json"));
        for (TokenizedBook b : DATASET.subList(0, DATASET.size() - 1)) {    // the last book is missing
            wrong.addDocument(b.id(), b.terms());
        }

        assertThrows(IllegalStateException.class, () -> benchmark.verify(wrong, DATASET, "test"));
    }

    // --- clear leaves each backend in an equivalent state ---------------------------------

    @Test
    void freshIndexBorraLosRestosDeUnaEjecucionAnterior() {
        for (Backend backend : IndexBenchmark.fileBackends()) {
            Path dir = tmp.resolve("leftovers").resolve(backend.name());
            InvertedIndex dirty = backend.open().apply(dir);
            dirty.addDocument(1, Set.of("whale", "ship"));
            dirty.flush();
            dirty.close();

            InvertedIndex fresh = benchmark.freshIndex(backend, dir, null);

            assertEquals(0, fresh.diskUsageBytes(), backend.name());
            assertEquals(List.of(), fresh.postings("whale"), backend.name());
            fresh.close();
        }
    }

    @Test
    void abrirYVaciarElIndiceNoSeMide() {
        Backend slowToOpen = new Backend("monolithic", dir -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return new MonolithicJsonIndex(dir.resolve("inverted_index.json"));
        });
        IndexBenchmark slow = new IndexBenchmark(new BenchmarkRunner(0, 2), tmp.resolve("slow"),
                List.of(slowToOpen), TOKENIZER, QUERIES, 1);

        for (BenchmarkRow row : rows(slow.build(DATASET.subList(0, 5)), "monolithic", "elapsed")) {
            assertTrue(row.value() < 150, "abrir el índice se coló en la medida: " + row.value());
        }
    }

    // --- Experiments ------------------------------------------------------------------------

    @Test
    void buildQueryYUpdateDanTiempoPorBackendYRepeticion() {
        Map<String, List<BenchmarkRow>> byExperiment = Map.of(
                "throughput", benchmark.build(DATASET),
                "per_query", benchmark.query(DATASET),
                "per_book", benchmark.update(DATASET));

        byExperiment.forEach((derivedMetric, rows) -> {
            for (String backend : List.of("monolithic", "hierarchical")) {
                assertEquals(List.of(1, 2, 3), rows(rows, backend, "elapsed").stream().map(BenchmarkRow::repetition).toList());
                assertEquals(3, rows(rows, backend, derivedMetric).size(), backend + "/" + derivedMetric);
            }
        });
    }

    @Test
    void diskMideBytesFicherosYBloques() {
        List<BenchmarkRow> disk = benchmark.disk(DATASET);

        assertEquals(1, rows(disk, "monolithic", "files").get(0).value());         // a single JSON
        double terms = rows(disk, "hierarchical", "terms").get(0).value();
        assertEquals(terms, rows(disk, "hierarchical", "files").get(0).value());   // one file per term
        for (String backend : List.of("monolithic", "hierarchical")) {
            assertTrue(rows(disk, backend, "bytes").get(0).value() > 0);
            assertTrue(rows(disk, backend, "allocated_bytes").get(0).value() >= rows(disk, backend, "bytes").get(0).value());
        }
    }

    @Test
    void memoryDaHeapTrasConstruirYTrasAbrir() {
        List<BenchmarkRow> memory = benchmark.memory(DATASET);

        for (String backend : List.of("monolithic", "hierarchical")) {
            assertEquals(1, rows(memory, backend, "heap_after_build").size());
            assertEquals(1, rows(memory, backend, "heap_after_open").size());
        }
    }

    @Test
    void hayUnaSerieDeMedidasPorCadaTamanoYSeEscribenLosCincoCsv() {
        Path results = tmp.resolve("benchmarks/results");
        Map<String, List<BenchmarkRow>> all = benchmark.runAll(DATASET, List.of(20, 40));

        IndexBenchmark.writeResults(results, all);

        assertEquals(List.of("index_build", "index_query", "index_update", "index_memory", "index_disk"),
                new ArrayList<>(all.keySet()));
        for (Map.Entry<String, List<BenchmarkRow>> e : all.entrySet()) {
            Set<Integer> sizes = e.getValue().stream().map(BenchmarkRow::datasetSize).collect(Collectors.toSet());
            assertEquals(Set.of(20, 40), sizes, e.getKey());
            Path file = results.resolve("java_" + e.getKey() + ".csv");
            assertTrue(Files.exists(file));
            assertEquals(e.getValue().size(), CsvResults.read(file).size());
        }
    }

    @Test
    void tamanoFueraDelDatasetEsUnError() {
        assertThrows(IllegalArgumentException.class, () -> benchmark.runAll(DATASET, List.of(41)));
        assertThrows(IllegalArgumentException.class, () -> benchmark.runAll(DATASET, List.of(1)));
    }

    // --- Synthetic dataset ------------------------------------------------------------------

    @Test
    void elDatasetZipfEsReproducibleYLasConsultasTienenSelectividadDistinta() {
        assertEquals(BenchmarkBooks.syntheticZipf(10, 300, 5000, 3), BenchmarkBooks.syntheticZipf(10, 300, 5000, 3));

        Map<String, Integer> booksWith = new HashMap<>();
        for (TokenizedBook b : DATASET) {
            for (String word : List.of("adventure", "father")) {             // rank 10 and rank 2560
                if (b.terms().contains(word)) {
                    booksWith.merge(word, 1, Integer::sum);
                }
            }
        }
        assertTrue(booksWith.getOrDefault("adventure", 0) > booksWith.getOrDefault("father", 0), booksWith.toString());
    }

    // --- Mongo (skipped if it is not running) ---------------------------------------------

    @Test
    void mongoRecibeLosMismosTerminosYQuedaVacioEntreRepeticiones() {
        String uri = System.getenv().getOrDefault("MONGO_URI", "mongodb://localhost:27017");
        assumeTrue(IndexBenchmark.mongoAvailable(uri), "MongoDB no está disponible en " + uri + ": test saltado");
        Backend mongo = IndexBenchmark.mongoBackend(uri, "search_engine_test", "bench_" + System.nanoTime());
        List<Backend> backends = new ArrayList<>(IndexBenchmark.fileBackends());
        backends.add(mongo);
        IndexBenchmark withMongo = new IndexBenchmark(new BenchmarkRunner(1, 2), tmp.resolve("mongo"),
                backends, TOKENIZER, QUERIES, 1);

        List<BenchmarkRow> build = withMongo.build(DATASET);                   // verify on each backend
        List<BenchmarkRow> update = withMongo.update(DATASET);
        List<BenchmarkRow> disk = withMongo.disk(DATASET);

        assertEquals(2, rows(build, "mongo", "elapsed").size());
        assertEquals(2, rows(update, "mongo", "elapsed").size());
        assertTrue(rows(disk, "mongo", "files").isEmpty());                     // Mongo does not write to the folder
        assertEquals(rows(disk, "monolithic", "postings").get(0).value(), rows(disk, "mongo", "postings").get(0).value());

        InvertedIndex left = mongo.open().apply(tmp);                           // discard left it empty
        assertEquals(List.of(), left.postings("whale"));
        left.clear();
        left.close();
    }
}
