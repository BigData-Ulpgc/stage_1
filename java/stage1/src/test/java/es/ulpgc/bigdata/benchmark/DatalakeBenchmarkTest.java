package es.ulpgc.bigdata.benchmark;

import es.ulpgc.bigdata.benchmark.DatalakeBenchmark.Structure;
import es.ulpgc.bigdata.datalake.BookBasedDatalake;
import es.ulpgc.bigdata.datalake.Datalake;
import es.ulpgc.bigdata.datalake.RangeBasedDatalake;
import es.ulpgc.bigdata.datalake.TimeBasedDatalake;
import es.ulpgc.bigdata.model.RawBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DatalakeBenchmarkTest {

    /** 30 small books: fast, but with several ranges of 1000 and several simulated hours. */
    private static final List<RawBook> BOOKS = BenchmarkBooks.synthetic(30, 2, 7);

    @TempDir Path tmp;

    private DatalakeBenchmark benchmark;

    @BeforeEach
    void setUp() {
        benchmark = new DatalakeBenchmark(new BenchmarkRunner(1, 3), tmp.resolve("work"),
                DatalakeBenchmark.defaultStructures());
    }

    private static List<BenchmarkRow> metric(List<BenchmarkRow> rows, String structure, String metric) {
        return rows.stream().filter(r -> r.structure().equals(structure) && r.metric().equals(metric)).toList();
    }

    private static double single(List<BenchmarkRow> rows, String structure, String metric) {
        List<BenchmarkRow> found = metric(rows, structure, metric);
        assertEquals(1, found.size(), structure + "/" + metric);
        return found.get(0).value();
    }

    private static Set<Integer> ids(List<RawBook> books) {
        return books.stream().map(RawBook::id).collect(Collectors.toSet());
    }

    // --- Criterion: the three structures use exactly the same books -----------------

    @Test
    void lasTresEstructurasGuardanExactamenteLosMismosLibros() {
        benchmark.write(BOOKS);

        List<Datalake> written = List.of(
                new BookBasedDatalake(tmp.resolve("work/write/book")),
                new RangeBasedDatalake(tmp.resolve("work/write/range")),
                new TimeBasedDatalake(tmp.resolve("work/write/time")));
        for (Datalake d : written) {
            assertEquals(ids(BOOKS), Set.copyOf(d.listBookIds()), d.name());
            for (RawBook book : BOOKS) {
                assertTrue(d.locate(book.id()).isPresent());
            }
        }
    }

    @Test
    void laEstructuraTimeRepartenLosLibrosEnVariasHoras() throws Exception {
        benchmark.write(BOOKS);

        // 30 books at 10 per hour = 3 hour folders (with the real clock it would be 1).
        try (var days = Files.list(tmp.resolve("work/write/time"))) {
            long hours = 0;
            for (Path day : days.toList()) {
                try (var h = Files.list(day)) {
                    hours += h.count();
                }
            }
            assertEquals(3, hours);
        }
    }

    // --- Criterion: preparation stays out of the measured time ------------------------

    @Test
    void crearYVaciarElDatalakeNoSeMide() {
        // A structure whose creation takes 300 ms: if the setup were measured, it would show.
        Structure slow = new Structure("book", dir -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return new BookBasedDatalake(dir);
        });
        DatalakeBenchmark withSlowSetup = new DatalakeBenchmark(new BenchmarkRunner(1, 3), tmp.resolve("slow"), List.of(slow));

        for (BenchmarkRow row : metric(withSlowSetup.write(BOOKS), "book", "elapsed")) {
            assertTrue(row.value() < 300, "el setup se coló en la medida: " + row.value() + " ms");
        }
    }

    // --- The five experiments --------------------------------------------------------------

    @Test
    void writeDaTiempoYThroughputPorRepeticion() {
        List<BenchmarkRow> rows = benchmark.write(BOOKS);

        for (String s : List.of("book", "range", "time")) {
            List<BenchmarkRow> elapsed = metric(rows, s, "elapsed");
            List<BenchmarkRow> throughput = metric(rows, s, "throughput");
            assertEquals(List.of(1, 2, 3), elapsed.stream().map(BenchmarkRow::repetition).toList());
            for (int i = 0; i < 3; i++) {
                double expected = BOOKS.size() / (elapsed.get(i).value() / 1000.0);
                assertEquals(expected, throughput.get(i).value(), expected * 0.01 + 1);     // same run
                assertEquals("books_per_s", throughput.get(i).unit());
            }
        }
    }

    @Test
    void lookupEncuentraTodosLosLibros() {
        List<BenchmarkRow> rows = benchmark.lookup(BOOKS);          // if any were missing, it would throw an exception

        for (String s : List.of("book", "range", "time")) {
            assertEquals(3, metric(rows, s, "elapsed").size());
            assertEquals(3, metric(rows, s, "per_lookup").size());
        }
    }

    @Test
    void incrementalDetectaExactamenteLosLibrosNuevos() {
        List<BenchmarkRow> rows = benchmark.incremental(BOOKS);

        for (String s : List.of("book", "range", "time")) {
            assertEquals(3.0, metric(rows, s, "detected").get(0).value());     // 10 % of 30
        }
    }

    @Test
    void recoverySinPerdidasNiDuplicados() {
        List<BenchmarkRow> rows = benchmark.recovery(BOOKS);

        for (String s : List.of("book", "range", "time")) {
            assertEquals(3.0, single(rows, s, "recovered"), s);                 // books 0, 10 and 20
            assertEquals(0.0, single(rows, s, "lost"), s);
            assertEquals(0.0, single(rows, s, "duplicates"), s);
        }
    }

    @Test
    void elFalloSimuladoEsReproducible() {
        // Two complete runs damage and recover exactly the same books.
        assertEquals(single(benchmark.recovery(BOOKS), "time", "recovered"),
                single(benchmark.recovery(BOOKS), "time", "recovered"));
    }

    @Test
    void storageCuentaFicherosCarpetasYBytes() {
        List<BenchmarkRow> rows = benchmark.storage(BOOKS);

        for (String s : List.of("book", "range", "time")) {
            assertEquals(60.0, single(rows, s, "files"), s);                    // header + body per book
            assertTrue(single(rows, s, "allocated_bytes") >= single(rows, s, "bytes"), s);
        }
        assertEquals(30.0, single(rows, "book", "directories"));                // one per book
        assertTrue(single(rows, "range", "directories") < 30);                  // one per range
        assertEquals(single(rows, "book", "bytes"), single(rows, "range", "bytes"));   // same content
    }

    // --- Results on disk -------------------------------------------------------------------

    @Test
    void runAllEscribeLosCincoCsvDelSpec() {
        Map<String, List<BenchmarkRow>> all = benchmark.runAll(BOOKS);
        Path results = tmp.resolve("benchmarks/results");

        DatalakeBenchmark.writeResults(results, all);

        for (String experiment : List.of("datalake_write", "datalake_lookup", "datalake_incremental",
                "datalake_recovery", "datalake_storage")) {
            Path file = results.resolve("java_" + experiment + ".csv");
            assertTrue(Files.exists(file), experiment);
            List<BenchmarkRow> read = CsvResults.read(file);
            // The CSV rounds to 3 decimals: the lines are compared as they are written.
            assertEquals(all.get(experiment).stream().map(BenchmarkRow::toCsvLine).toList(),
                    read.stream().map(BenchmarkRow::toCsvLine).toList(), experiment);
            assertTrue(read.stream().allMatch(r -> r.datasetSize() == BOOKS.size()));
        }
    }

    @Test
    void losLibrosPuedenLeerseDeUnDatalakeYaLleno() {
        Datalake source = new BookBasedDatalake(tmp.resolve("source"));
        BOOKS.forEach(source::save);

        List<RawBook> loaded = BenchmarkBooks.fromDatalake(source);

        assertEquals(new ArrayList<>(ids(BOOKS)).size(), loaded.size());
        assertEquals(Set.copyOf(BOOKS), Set.copyOf(loaded));
    }
}