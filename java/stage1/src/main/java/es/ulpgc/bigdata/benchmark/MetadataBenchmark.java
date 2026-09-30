package es.ulpgc.bigdata.benchmark;

import es.ulpgc.bigdata.benchmark.BenchmarkRunner.Scenario;
import es.ulpgc.bigdata.datamart.metadata.MetadataRepository;
import es.ulpgc.bigdata.datamart.metadata.SqliteMetadataRepository;
import es.ulpgc.bigdata.datamart.metadata.SqliteSchema;
import es.ulpgc.bigdata.model.BookMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Benchmark del almacén de metadatos (SPEC, sección 9):
 *
 *   metadata_insert   saveAll por lotes de un dataset de N libros    elapsed (ms) + throughput (rows/s)
 *   metadata_query    Q consultas de cada tipo                        find_by_id / find_by_author /
 *                                                                     find_by_title (ms) + *_avg (µs)
 *
 * Se repite para varios N crecientes, siempre prefijos del MISMO dataset.
 * Los metadatos ya vienen construidos: el parseo del header NO está en el tiempo.
 *
 * Dos variantes: "sqlite" (con los índices del SPEC sobre author y title) y
 * "sqlite_no_index" (sin ellos), para ver qué aportan esos índices.
 */
public class MetadataBenchmark {

    public static final String LANGUAGE = "java";

    /** Libros por lote en metadata_insert (cada lote es una transacción). */
    public static final int DEFAULT_BATCH_SIZE = 1000;
    /** Consultas de cada tipo en metadata_query. */
    public static final int DEFAULT_QUERIES = 1000;

    /** Una variante a comparar: su nombre en el CSV y cómo abrir un repositorio vacío. */
    public record Backend(String name, Function<Path, MetadataRepository> open) {
    }

    public static List<Backend> defaultBackends() {
        return List.of(
                new Backend("sqlite", SqliteMetadataRepository::new),
                new Backend("sqlite_no_index", db -> {
                    MetadataRepository repo = new SqliteMetadataRepository(db);
                    dropAuthorAndTitleIndexes(db);
                    return repo;
                }));
    }

    private final BenchmarkRunner runner;
    private final Path workDir;
    private final List<Backend> backends;
    private final int batchSize;
    private final int queries;

    public MetadataBenchmark(BenchmarkRunner runner, Path workDir, List<Backend> backends,
                             int batchSize, int queries) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.workDir = Objects.requireNonNull(workDir, "workDir");
        this.backends = List.copyOf(backends);
        if (batchSize < 1 || queries < 1) {
            throw new IllegalArgumentException("batchSize y queries deben ser >= 1");
        }
        this.batchSize = batchSize;
        this.queries = queries;
    }

    /**
     * Los dos experimentos para cada tamaño. Cada tamaño N usa los N primeros libros
     * de 'dataset', así todos los tamaños son partes del mismo dataset.
     */
    public Map<String, List<BenchmarkRow>> runAll(List<BookMetadata> dataset, List<Integer> sizes) {
        List<BenchmarkRow> insert = new ArrayList<>();
        List<BenchmarkRow> query = new ArrayList<>();
        for (int n : sizes) {
            if (n < 1 || n > dataset.size()) {
                throw new IllegalArgumentException("Tamaño " + n + " fuera de 1.." + dataset.size());
            }
            List<BookMetadata> books = dataset.subList(0, n);
            insert.addAll(insert(books));
            query.addAll(query(books));
        }
        Map<String, List<BenchmarkRow>> results = new LinkedHashMap<>();
        results.put("metadata_insert", insert);
        results.put("metadata_query", query);
        return results;
    }

    public static void writeResults(Path resultsDir, Map<String, List<BenchmarkRow>> results) {
        results.forEach((experiment, rows) ->
                CsvResults.write(CsvResults.fileFor(resultsDir, LANGUAGE, experiment), rows));
    }

    // ------------------------------------------------------------------
    // metadata_insert: saveAll por lotes en una base vacía
    // ------------------------------------------------------------------

    public List<BenchmarkRow> insert(List<BookMetadata> books) {
        List<List<BookMetadata>> batches = batches(books, batchSize);    // se trocea ANTES de medir
        List<BenchmarkRow> rows = new ArrayList<>();
        for (Backend backend : backends) {
            Path db = dbFile("insert", backend, books.size());
            MetadataRepository[] repo = new MetadataRepository[1];
            List<BenchmarkRow> elapsed = runner.run(scenario("metadata_insert", backend, books.size()),
                    () -> repo[0] = freshRepository(backend, db, repo[0]),    // setup: base vacía
                    () -> {
                        for (List<BookMetadata> batch : batches) {
                            repo[0].saveAll(batch);
                        }
                    });
            require(repo[0].count() == books.size(), backend.name() + ": no se insertaron todas las filas");
            repo[0].close();
            rows.addAll(elapsed);
            for (BenchmarkRow e : elapsed) {
                rows.add(derived(e, "throughput", "rows_per_s", books.size() / (Math.max(e.value(), 0.001) / 1000.0)));
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // metadata_query: carga de consultas definida, un tipo cada vez
    // ------------------------------------------------------------------

    /**
     * Carga: 'queries' consultas de cada tipo, elegidas con semilla fija entre los valores
     * que EXISTEN en la base (siempre encuentran algo). La misma carga para todas las variantes.
     */
    public List<BenchmarkRow> query(List<BookMetadata> books) {
        Random random = new Random(42);
        List<Integer> ids = new ArrayList<>();
        List<String> authors = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < queries; i++) {
            BookMetadata pick = books.get(random.nextInt(books.size()));
            ids.add(pick.bookId());
            authors.add(pick.author());
            titles.add(pick.title());
        }

        List<BenchmarkRow> rows = new ArrayList<>();
        for (Backend backend : backends) {
            Path db = dbFile("query", backend, books.size());
            MetadataRepository repo = freshRepository(backend, db, null);
            repo.saveAll(books);                                          // preparación única, sin medir
            int n = books.size();

            rows.addAll(queryType(backend, n, "find_by_id", ids,
                    id -> repo.findById(id).isPresent() ? 1 : 0));
            rows.addAll(queryType(backend, n, "find_by_author", authors,
                    author -> repo.findByAuthor(author).size()));
            rows.addAll(queryType(backend, n, "find_by_title", titles,
                    title -> repo.findByTitle(title).size()));
            repo.close();
        }
        return rows;
    }

    /** Un tipo de consulta: el nombre del tipo va en la columna metric. */
    private <T> List<BenchmarkRow> queryType(Backend backend, int n, String metric, List<T> workload,
                                             ToIntFunction<T> query) {
        long[] found = {0};
        List<BenchmarkRow> measured = runner.run(scenario("metadata_query", backend, n),
                () -> found[0] = 0,
                () -> {
                    for (T value : workload) {
                        found[0] += query.applyAsInt(value);              // usar el resultado
                    }
                });
        require(found[0] >= workload.size(), backend.name() + " " + metric + ": alguna consulta no encontró nada");

        List<BenchmarkRow> rows = new ArrayList<>();
        for (BenchmarkRow e : measured) {
            rows.add(derived(e, metric, "ms", e.value()));                                 // total de la carga
            rows.add(derived(e, metric + "_avg", "us", e.value() * 1000.0 / workload.size()));
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // Dataset sintético compartido
    // ------------------------------------------------------------------

    /**
     * Metadatos sintéticos: libro i -> autor i/10 y título i/2. Así CADA autor tiene 10 libros
     * y cada título 2, sea cual sea N, y los N primeros son siempre los mismos: al crecer N sólo
     * crece la tabla, no el número de resultados de cada consulta.
     */
    public static List<BookMetadata> syntheticDataset(int size) {
        List<BookMetadata> books = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int id = i + 1;
            books.add(new BookMetadata(id, "Title " + (i / 2), "Author " + (i / 10), "English",
                    "January 1, 2000",
                    Path.of("datalake/book/" + id + "/body.txt"),
                    Path.of("datalake/book/" + id + "/header.txt")));
        }
        return List.copyOf(books);
    }

    // ------------------------------------------------------------------
    // Auxiliares
    // ------------------------------------------------------------------

    /** Quita idx_books_author e idx_books_title (variante sqlite_no_index). */
    static void dropAuthorAndTitleIndexes(Path db) {
        try (Connection c = DriverManager.getConnection(SqliteSchema.jdbcUrl(db));
             Statement st = c.createStatement()) {
            st.execute("DROP INDEX IF EXISTS idx_books_author");
            st.execute("DROP INDEX IF EXISTS idx_books_title");
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron quitar los índices de " + db, e);
        }
    }

    private Path dbFile(String experiment, Backend backend, int n) {
        return workDir.resolve(experiment).resolve(backend.name() + "_" + n + ".db");
    }

    /** Cierra el repositorio anterior, borra el fichero y abre uno vacío (siempre en setup). */
    private static MetadataRepository freshRepository(Backend backend, Path db, MetadataRepository previous) {
        if (previous != null) {
            previous.close();
        }
        try {
            Files.deleteIfExists(db);
            Files.deleteIfExists(Path.of(db + "-journal"));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo borrar " + db, e);
        }
        return backend.open().apply(db);
    }

    private static List<List<BookMetadata>> batches(List<BookMetadata> books, int size) {
        List<List<BookMetadata>> batches = new ArrayList<>();
        for (int from = 0; from < books.size(); from += size) {
            batches.add(books.subList(from, Math.min(from + size, books.size())));
        }
        return batches;
    }

    private static Scenario scenario(String experiment, Backend backend, int n) {
        return new Scenario(LANGUAGE, experiment, backend.name(), n);
    }

    private static BenchmarkRow derived(BenchmarkRow e, String metric, String unit, double value) {
        return new BenchmarkRow(e.language(), e.experiment(), e.structure(), e.datasetSize(),
                e.repetition(), metric, value, unit);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    // ------------------------------------------------------------------
    // Ejecutable
    // ------------------------------------------------------------------

    /** Uso: MetadataBenchmark [tamaños separados por comas]   (por defecto 1000,10000,100000) */
    public static void main(String[] args) {
        List<Integer> sizes = new ArrayList<>();
        for (String s : (args.length > 0 ? args[0] : "1000,10000,100000").split(",")) {
            sizes.add(Integer.parseInt(s.strip()));
        }
        int max = sizes.stream().mapToInt(Integer::intValue).max().orElseThrow();

        MetadataBenchmark benchmark = new MetadataBenchmark(BenchmarkRunner.standard(),
                Path.of("benchmarks/work/metadata"), defaultBackends(), DEFAULT_BATCH_SIZE, DEFAULT_QUERIES);
        Map<String, List<BenchmarkRow>> results = benchmark.runAll(syntheticDataset(max), sizes);
        writeResults(Path.of("benchmarks/results"), results);
        results.forEach((experiment, rows) -> System.out.println(experiment + ": " + rows.size() + " filas"));
    }
}