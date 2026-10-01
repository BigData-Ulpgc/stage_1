package es.ulpgc.bigdata.benchmark;

import es.ulpgc.bigdata.benchmark.MetadataBenchmark.Backend;
import es.ulpgc.bigdata.datamart.metadata.SqliteMetadataRepository;
import es.ulpgc.bigdata.datamart.metadata.SqliteSchema;
import es.ulpgc.bigdata.model.BookMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MetadataBenchmarkTest {

    private static final List<BookMetadata> DATASET = MetadataBenchmark.syntheticDataset(400);
    private static final List<Integer> SIZES = List.of(100, 400);

    @TempDir Path tmp;

    private MetadataBenchmark benchmark;

    @BeforeEach
    void setUp() {
        benchmark = new MetadataBenchmark(new BenchmarkRunner(1, 3), tmp.resolve("work"),
                MetadataBenchmark.defaultBackends(), 50, 40);
    }

    private static Set<String> metrics(List<BenchmarkRow> rows) {
        return rows.stream().map(BenchmarkRow::metric).collect(Collectors.toSet());
    }

    // --- Criterio: cada tipo de consulta queda distinguido ---------------------------

    @Test
    void cadaTipoDeConsultaTieneSuPropiaMetrica() {
        List<BenchmarkRow> rows = benchmark.query(DATASET.subList(0, 100));

        assertEquals(Set.of("find_by_id", "find_by_id_avg", "find_by_author", "find_by_author_avg",
                "find_by_title", "find_by_title_avg"), metrics(rows));
        for (String backend : List.of("sqlite", "sqlite_no_index")) {
            for (String metric : List.of("find_by_id", "find_by_author", "find_by_title")) {
                List<Integer> reps = rows.stream()
                        .filter(r -> r.structure().equals(backend) && r.metric().equals(metric))
                        .map(BenchmarkRow::repetition).toList();
                assertEquals(List.of(1, 2, 3), reps, backend + "/" + metric);
            }
        }
    }

    // --- Criterio: se puede representar cómo cambia el tiempo al crecer N ---------------

    @Test
    void hayUnaSerieDeMedidasPorCadaTamano() {
        Map<String, List<BenchmarkRow>> all = benchmark.runAll(DATASET, SIZES);

        for (String experiment : List.of("metadata_insert", "metadata_query")) {
            Set<Integer> sizes = all.get(experiment).stream().map(BenchmarkRow::datasetSize).collect(Collectors.toSet());
            assertEquals(Set.copyOf(SIZES), sizes, experiment);
        }
        // Una curva por variante y consulta: 2 tamaños × 3 repeticiones.
        long points = all.get("metadata_query").stream()
                .filter(r -> r.structure().equals("sqlite") && r.metric().equals("find_by_author")).count();
        assertEquals(6, points);
    }

    @Test
    void losTamanosSonPrefijosDelMismoDataset() {
        List<BookMetadata> small = MetadataBenchmark.syntheticDataset(100);

        assertEquals(small, DATASET.subList(0, 100));
    }

    @Test
    void elNumeroDeResultadosPorConsultaNoCreceConN() {
        for (int n : SIZES) {
            List<BookMetadata> books = DATASET.subList(0, n);
            assertEquals(10, books.stream().filter(b -> b.author().equals("Author 3")).count());
            assertEquals(2, books.stream().filter(b -> b.title().equals("Title 7")).count());
        }
    }

    // --- Inserción ----------------------------------------------------------------------------

    @Test
    void insertDaTiempoYThroughputPorVariante() {
        List<BenchmarkRow> rows = benchmark.insert(DATASET.subList(0, 100));   // si faltaran filas, lanzaría excepción

        for (String backend : List.of("sqlite", "sqlite_no_index")) {
            assertEquals(3, rows.stream().filter(r -> r.structure().equals(backend) && r.metric().equals("elapsed")).count());
            assertEquals(3, rows.stream().filter(r -> r.structure().equals(backend) && r.metric().equals("throughput")).count());
        }
    }

    @Test
    void abrirYVaciarLaBaseNoSeMide() {
        Backend slowToOpen = new Backend("sqlite", db -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return new SqliteMetadataRepository(db);
        });
        MetadataBenchmark slow = new MetadataBenchmark(new BenchmarkRunner(1, 2), tmp.resolve("slow"),
                List.of(slowToOpen), 50, 10);

        for (BenchmarkRow row : slow.insert(DATASET.subList(0, 20))) {
            if (row.metric().equals("elapsed")) {
                assertTrue(row.value() < 300, "abrir la base se coló en la medida: " + row.value());
            }
        }
    }

    // --- La variante sin índices -----------------------------------------------------------------

    private static List<String> indexes(Path db) throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(SqliteSchema.jdbcUrl(db));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index' ORDER BY name")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    @Test
    void laVarianteSinIndicesQuitaSoloLosDeAuthorYTitle() throws Exception {
        Path withIdx = tmp.resolve("a.db");
        Path noIdx = tmp.resolve("b.db");
        MetadataBenchmark.defaultBackends().get(0).open().apply(withIdx).close();
        MetadataBenchmark.defaultBackends().get(1).open().apply(noIdx).close();

        assertEquals(List.of("idx_books_author", "idx_books_title"), indexes(withIdx));
        assertEquals(List.of(), indexes(noIdx));            // book_id sigue siendo PRIMARY KEY
    }

    // --- Resultados en disco -----------------------------------------------------------------------

    @Test
    void escribeLosDosCsvDelSpec() {
        Path results = tmp.resolve("benchmarks/results");
        Map<String, List<BenchmarkRow>> all = benchmark.runAll(DATASET, SIZES);

        MetadataBenchmark.writeResults(results, all);

        for (String experiment : List.of("metadata_insert", "metadata_query")) {
            Path file = results.resolve("java_" + experiment + ".csv");
            assertTrue(Files.exists(file));
            assertEquals(all.get(experiment).size(), CsvResults.read(file).size());
        }
    }

    @Test
    void tamanoMayorQueElDatasetEsUnError() {
        assertThrows(IllegalArgumentException.class, () -> benchmark.runAll(DATASET, List.of(500)));
    }
}