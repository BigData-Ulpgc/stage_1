package es.ulpgc.bigdata.benchmark;

import es.ulpgc.bigdata.benchmark.BenchmarkRunner.Scenario;
import es.ulpgc.bigdata.config.AppConfig;
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
 * Benchmark of the metadata store (SPEC, section 9):
 *
 *   metadata_insert   batched saveAll of a dataset of N books        elapsed (ms) + throughput (rows/s)
 *   metadata_query    Q queries of each type                          find_by_id / find_by_author /
 *                                                                     find_by_title (ms) + *_avg (µs)
 *
 * It is repeated for several increasing N, always prefixes of the SAME dataset.
 * The metadata comes already built: parsing the header is NOT in the time.
 *
 * Two variants: "sqlite" (with the SPEC indexes on author and title) and
 * "sqlite_no_index" (without them), to see what those indexes contribute.
 */
public class MetadataBenchmark {

    public static final String LANGUAGE = "java";

    /** Books per batch in metadata_insert (each batch is one transaction). */
    public static final int DEFAULT_BATCH_SIZE = 1000;
    /** Queries of each type in metadata_query. */
    public static final int DEFAULT_QUERIES = 1000;

    /** A variant to compare: its name in the CSV and how to open an empty repository. */
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
     * The two experiments for each size. Each size N uses the first N books
     * of 'dataset', so all sizes are parts of the same dataset.
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
    // metadata_insert: batched saveAll into an empty database
    // ------------------------------------------------------------------

    public List<BenchmarkRow> insert(List<BookMetadata> books) {
        List<List<BookMetadata>> batches = batches(books, batchSize);    // split BEFORE measuring
        List<BenchmarkRow> rows = new ArrayList<>();
        for (Backend backend : backends) {
            Path db = dbFile("insert", backend, books.size());
            MetadataRepository[] repo = new MetadataRepository[1];
            List<BenchmarkRow> elapsed = runner.run(scenario("metadata_insert", backend, books.size()),
                    () -> repo[0] = freshRepository(backend, db, repo[0]),    // setup: empty database
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
    // metadata_query: defined query workload, one type at a time
    // ------------------------------------------------------------------

    /**
     * Workload: 'queries' queries of each type, chosen with a fixed seed among the values
     * that EXIST in the database (they always find something). The same workload for every variant.
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
            repo.saveAll(books);                                          // one-off preparation, not measured
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

    /** A query type: the type name goes in the metric column. */
    private <T> List<BenchmarkRow> queryType(Backend backend, int n, String metric, List<T> workload,
                                             ToIntFunction<T> query) {
        long[] found = {0};
        List<BenchmarkRow> measured = runner.run(scenario("metadata_query", backend, n),
                () -> found[0] = 0,
                () -> {
                    for (T value : workload) {
                        found[0] += query.applyAsInt(value);              // use the result
                    }
                });
        require(found[0] >= workload.size(), backend.name() + " " + metric + ": alguna consulta no encontró nada");

        List<BenchmarkRow> rows = new ArrayList<>();
        for (BenchmarkRow e : measured) {
            rows.add(derived(e, metric, "ms", e.value()));                                 // total of the workload
            rows.add(derived(e, metric + "_avg", "us", e.value() * 1000.0 / workload.size()));
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // Shared synthetic dataset
    // ------------------------------------------------------------------

    /**
     * Synthetic metadata: book i -> author i/10 and title i/2. This way EACH author has 10 books
     * and each title 2, whatever N is, and the first N are always the same: as N grows only
     * the table grows, not the number of results of each query.
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
    // Helpers
    // ------------------------------------------------------------------

    /** Removes idx_books_author and idx_books_title (sqlite_no_index variant). */
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

    /** Closes the previous repository, deletes the file and opens an empty one (always in setup). */
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
    // Executable
    // ------------------------------------------------------------------

    /** Usage: MetadataBenchmark [sizes separated by commas]   (default 1000,10000,100000) */
    public static void main(String[] args) {
        List<Integer> sizes = new ArrayList<>();
        for (String s : (args.length > 0 ? args[0] : "1000,10000,100000").split(",")) {
            sizes.add(Integer.parseInt(s.strip()));
        }
        int max = sizes.stream().mapToInt(Integer::intValue).max().orElseThrow();

        AppConfig config = AppConfig.load();
        MetadataBenchmark benchmark = new MetadataBenchmark(BenchmarkRunner.standard(),
                config.benchmarkWorkDir("metadata"), defaultBackends(), DEFAULT_BATCH_SIZE, DEFAULT_QUERIES);
        Map<String, List<BenchmarkRow>> results = benchmark.runAll(syntheticDataset(max), sizes);
        writeResults(config.benchmarkResultsDir(), results);
        results.forEach((experiment, rows) -> System.out.println(experiment + ": " + rows.size() + " filas"));
    }
}