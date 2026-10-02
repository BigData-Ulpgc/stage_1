package es.ulpgc.bigdata.benchmark;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import es.ulpgc.bigdata.benchmark.BenchmarkRunner.Scenario;
import es.ulpgc.bigdata.config.AppConfig;
import es.ulpgc.bigdata.config.InvertedIndexFactory;
import es.ulpgc.bigdata.datalake.BookBasedDatalake;
import es.ulpgc.bigdata.datalake.DatalakeStats;
import es.ulpgc.bigdata.datamart.index.InMemoryInvertedIndex;
import es.ulpgc.bigdata.datamart.index.InvertedIndex;
import es.ulpgc.bigdata.datamart.index.MongoInvertedIndex;
import es.ulpgc.bigdata.datamart.index.Tokenizer;
import es.ulpgc.bigdata.model.RawBook;
import es.ulpgc.bigdata.query.SearchService;
import org.bson.Document;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Benchmark of the three inverted indexes (monolithic, hierarchical, mongo), with the
 * experiments of section 9 of the SPEC:
 *
 *   index_build    empty index -> N books added + flush                elapsed (ms) + throughput (books/s)
 *   index_query    the queries.txt workload (AND) on the on-disk index  elapsed (ms) + per_query (µs)
 *   index_update   index with N-k books -> add k, flush per book        elapsed (ms) + per_book (ms)
 *   index_memory   Java heap with the index built / reopened            heap_after_build, heap_after_open (bytes)
 *   index_disk     disk usage after building                            bytes (+ files, allocated_bytes) + terms, postings
 *
 * Rules to keep the comparison fair:
 *  - Books are tokenized ONCE, before everything (tokenizeAll). The three backends receive
 *    the SAME list of TokenizedBook: the tokenizer is not in any measurement.
 *  - Before each repetition, freshIndex leaves the backend empty (clear) and checks that it is.
 *  - After measuring, it checks that the index gives the same results as an in-memory
 *    index built with the same terms (verify). If not, the benchmark fails.
 *  - Each size N uses the first N books of the dataset: the sizes are prefixes of the same dataset.
 */
public class IndexBenchmark {

    public static final String LANGUAGE = "java";

    /** Times the queries.txt workload is repeated inside one measurement (10 queries alone take µs). */
    public static final int DEFAULT_QUERY_ROUNDS = 100;

    /** Mongo database for the benchmark: never the one of the real index (search_engine). */
    public static final String BENCH_DATABASE = "search_engine_bench";

    /** An already tokenized book: the only thing the indexes receive. */
    public record TokenizedBook(int id, Set<String> terms) {
        public TokenizedBook {
            terms = Set.copyOf(terms);                         // unmodifiable: nobody changes it between backends
        }
    }

    /**
     * A backend to compare: its name in the CSV and how to open the index that lives in a folder.
     * Opening the same folder twice must give the same index (this is how it is "reopened" after a flush).
     */
    public record Backend(String name, Function<Path, InvertedIndex> open) {
    }

    /**
     * monolithic and hierarchical, created by InvertedIndexFactory with the data folder
     * pointing to 'dir': the paths inside it (datamarts/inverted_index.json...) are decided by AppConfig.
     */
    public static List<Backend> fileBackends() {
        AppConfig base = AppConfig.defaults();
        return List.of("monolithic", "hierarchical").stream()
                .map(name -> new Backend(name, dir -> InvertedIndexFactory.create(name, base.withDataDir(dir))))
                .toList();
    }

    /** Mongo does not use the folder: always the same collection, which freshIndex empties. */
    public static Backend mongoBackend(String uri, String database, String collection) {
        return new Backend("mongo", dir -> new MongoInvertedIndex(uri, database, collection));
    }

    /** Does Mongo answer at this address? (to skip it instead of failing if it is not running) */
    public static boolean mongoAvailable(String uri) {
        MongoClientSettings quick = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(1500, TimeUnit.MILLISECONDS))
                .build();
        try (MongoClient probe = MongoClients.create(quick)) {
            probe.getDatabase("admin").runCommand(new Document("ping", 1));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Tokenizes all the books. Called BEFORE any measurement. */
    public static List<TokenizedBook> tokenizeAll(List<RawBook> books, Tokenizer tokenizer) {
        List<TokenizedBook> tokenized = new ArrayList<>(books.size());
        for (RawBook book : books) {
            tokenized.add(new TokenizedBook(book.id(), tokenizer.uniqueTerms(book.body())));
        }
        return List.copyOf(tokenized);
    }

    private final BenchmarkRunner runner;
    private final Path workDir;
    private final List<Backend> backends;
    private final Tokenizer tokenizer;
    private final List<String> queries;
    private final int queryRounds;

    public IndexBenchmark(BenchmarkRunner runner, Path workDir, List<Backend> backends,
                          Tokenizer tokenizer, List<String> queries, int queryRounds) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.workDir = Objects.requireNonNull(workDir, "workDir");
        this.backends = List.copyOf(backends);
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.queries = List.copyOf(queries);
        if (this.queries.isEmpty() || queryRounds < 1) {
            throw new IllegalArgumentException("hacen falta consultas y queryRounds >= 1");
        }
        this.queryRounds = queryRounds;
    }

    /** The five experiments for each size (prefixes of 'dataset'). */
    public Map<String, List<BenchmarkRow>> runAll(List<TokenizedBook> dataset, List<Integer> sizes) {
        Map<String, List<BenchmarkRow>> results = new LinkedHashMap<>();
        for (String experiment : List.of("index_build", "index_query", "index_update", "index_memory", "index_disk")) {
            results.put(experiment, new ArrayList<>());
        }
        for (int n : sizes) {
            if (n < 2 || n > dataset.size()) {
                throw new IllegalArgumentException("Tamaño " + n + " fuera de 2.." + dataset.size());
            }
            List<TokenizedBook> books = dataset.subList(0, n);
            results.get("index_build").addAll(build(books));
            results.get("index_query").addAll(query(books));
            results.get("index_update").addAll(update(books));
            results.get("index_memory").addAll(memory(books));
            results.get("index_disk").addAll(disk(books));
        }
        return results;
    }

    public static void writeResults(Path resultsDir, Map<String, List<BenchmarkRow>> results) {
        results.forEach((experiment, rows) ->
                CsvResults.write(CsvResults.fileFor(resultsDir, LANGUAGE, experiment), rows));
    }

    // ------------------------------------------------------------------
    // index_build: from an empty index to a persisted index with N books
    // ------------------------------------------------------------------

    /**
     * Measured: N addDocument calls with precomputed terms + ONE final flush (until it is on disk).
     * Not measured: reading books, tokenizing, emptying the index or opening the connection.
     */
    public List<BenchmarkRow> build(List<TokenizedBook> books) {
        List<BenchmarkRow> rows = new ArrayList<>();
        for (Backend backend : backends) {
            Path dir = dirFor("build", backend);
            InvertedIndex[] index = new InvertedIndex[1];
            List<BenchmarkRow> elapsed = runner.run(scenario("index_build", backend, books.size()),
                    () -> index[0] = freshIndex(backend, dir, index[0]),      // setup: empty index
                    () -> {
                        addAll(index[0], books);
                        index[0].flush();
                    });
            verify(index[0], books, backend.name() + " build");
            discard(index[0]);
            rows.addAll(elapsed);
            for (BenchmarkRow e : elapsed) {
                rows.add(derived(e, "throughput", "books_per_s", books.size() / (Math.max(e.value(), 0.001) / 1000.0)));
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // index_query: the same AND workload for all of them
    // ------------------------------------------------------------------

    /**
     * The index is built and REOPENED before measuring: this way what is on disk is queried
     * (for monolithic, the JSON already loaded into memory when opening; for the others, their files or Mongo).
     * Measured: queryRounds times the queries.txt workload with SearchService (includes tokenizing the query).
     */
    public List<BenchmarkRow> query(List<TokenizedBook> books) {
        int totalQueries = queryRounds * queries.size();
        List<BenchmarkRow> rows = new ArrayList<>();
        for (Backend backend : backends) {
            Path dir = dirFor("query", backend);
            InvertedIndex built = freshIndex(backend, dir, null);
            addAll(built, books);
            built.flush();
            built.close();

            InvertedIndex index = backend.open().apply(dir);
            verify(index, books, backend.name() + " query");                  // same results as the reference
            SearchService search = new SearchService(tokenizer, index);
            long[] found = {0};
            List<BenchmarkRow> elapsed = runner.run(scenario("index_query", backend, books.size()),
                    () -> found[0] = 0,
                    () -> {
                        for (int round = 0; round < queryRounds; round++) {
                            for (String q : queries) {
                                found[0] += search.search(q).size();          // use the result
                            }
                        }
                    });
            discard(index);
            rows.addAll(elapsed);
            for (BenchmarkRow e : elapsed) {
                rows.add(derived(e, "per_query", "us", e.value() * 1000.0 / totalQueries));
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // index_update: add k books to an index that already has N-k
    // ------------------------------------------------------------------

    /**
     * k = 10 % of N (minimum 1), like datalake_incremental.
     * Setup (not measured): empty index, N-k books, flush, close and reopen (as a later run
     * of the pipeline would find it).
     * Measured: the k books, each one with addDocument + flush, just like Indexer does (challenge 21).
     * Here monolithic rewrites the WHOLE JSON on every flush; hierarchical only the
     * files of the book's terms; mongo only those documents.
     */
    public List<BenchmarkRow> update(List<TokenizedBook> books) {
        int k = Math.max(1, books.size() / 10);
        List<TokenizedBook> base = books.subList(0, books.size() - k);
        List<TokenizedBook> added = books.subList(books.size() - k, books.size());

        List<BenchmarkRow> rows = new ArrayList<>();
        for (Backend backend : backends) {
            Path dir = dirFor("update", backend);
            InvertedIndex[] index = new InvertedIndex[1];
            List<BenchmarkRow> elapsed = runner.run(scenario("index_update", backend, books.size()),
                    () -> {                                                   // setup, not measured
                        InvertedIndex previous = freshIndex(backend, dir, index[0]);
                        addAll(previous, base);
                        previous.flush();
                        previous.close();
                        index[0] = backend.open().apply(dir);
                    },
                    () -> {
                        for (TokenizedBook book : added) {
                            index[0].addDocument(book.id(), book.terms());
                            index[0].flush();
                        }
                    });
            verify(index[0], books, backend.name() + " update");              // N-k + k == building N
            discard(index[0]);
            rows.addAll(elapsed);
            for (BenchmarkRow e : elapsed) {
                rows.add(derived(e, "per_book", "ms", e.value() / k));
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // index_memory: Java heap (estimate)
    // ------------------------------------------------------------------

    /**
     * Heap used (after requesting GC) before and after, with the index still alive:
     *   heap_after_build  index built with N books and flushed
     *   heap_after_open   index just opened from disk (what a running search engine takes)
     *
     * It is an ESTIMATE: System.gc() is a request, not an order, and some value may come out
     * negative or with a few KB of noise.
     *
     * Mongo limitation: here only the CLIENT heap is seen (driver, connections, pending work).
     * The data lives in another process, mongod, with its own cache (WiredTiger), which is not in
     * our heap and is also shared by all collections: it is not comparable with the heap.
     */
    public List<BenchmarkRow> memory(List<TokenizedBook> books) {
        List<BenchmarkRow> rows = new ArrayList<>();
        int n = books.size();
        for (Backend backend : backends) {
            Path dir = dirFor("memory", backend);
            freshIndex(backend, dir, null).close();

            long before = usedHeapAfterGc();
            InvertedIndex index = backend.open().apply(dir);
            addAll(index, books);
            index.flush();
            long afterBuild = usedHeapAfterGc();
            Reference.reachabilityFence(index);                               // so the GC does not consider it dead too early
            index.close();
            index = null;

            long beforeOpen = usedHeapAfterGc();
            InvertedIndex reopened = backend.open().apply(dir);
            long afterOpen = usedHeapAfterGc();
            Reference.reachabilityFence(reopened);
            verify(reopened, books, backend.name() + " memory");
            discard(reopened);

            rows.add(single(backend, n, "index_memory", "heap_after_build", afterBuild - before, "bytes"));
            rows.add(single(backend, n, "index_memory", "heap_after_open", afterOpen - beforeOpen, "bytes"));
        }
        return rows;
    }

    /** Heap in use after a couple of GCs (what remains is what is still alive). */
    static long usedHeapAfterGc() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    // ------------------------------------------------------------------
    // index_disk: bytes on disk after building
    // ------------------------------------------------------------------

    /**
     * bytes            diskUsageBytes() from the contract (Mongo: storageSize + totalIndexSize, compressed)
     * files            files in the folder (file backends only)
     * allocated_bytes  blocks the disk reserves (file backends only): with thousands of
     *                  small files, hierarchical takes much more than its bytes add up to
     * terms, postings  size of the logical index: the SAME in every backend (same terms)
     */
    public List<BenchmarkRow> disk(List<TokenizedBook> books) {
        long terms = distinctTerms(books);
        long postings = books.stream().mapToLong(b -> b.terms().size()).sum();
        List<BenchmarkRow> rows = new ArrayList<>();
        int n = books.size();
        for (Backend backend : backends) {
            Path dir = dirFor("disk", backend);
            InvertedIndex index = freshIndex(backend, dir, null);
            addAll(index, books);
            index.flush();
            verify(index, books, backend.name() + " disk");

            rows.add(single(backend, n, "index_disk", "bytes", index.diskUsageBytes(), "bytes"));
            if (Files.isDirectory(dir)) {                                     // Mongo does not write to the folder
                rows.add(single(backend, n, "index_disk", "files", DatalakeStats.of(dir).files(), "count"));
                rows.add(single(backend, n, "index_disk", "allocated_bytes", DatalakeBenchmark.allocatedBytes(dir), "bytes"));
            }
            rows.add(single(backend, n, "index_disk", "terms", terms, "count"));
            rows.add(single(backend, n, "index_disk", "postings", postings, "count"));
            discard(index);
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // Equivalent initial state and result checking
    // ------------------------------------------------------------------

    /**
     * Closes the previous index, deletes whatever was there (clear) and opens a new one.
     * Checks that the new one is really empty: no file in the folder and no
     * posting list for the query terms. Always in the setup, never measured.
     */
    InvertedIndex freshIndex(Backend backend, Path dir, InvertedIndex previous) {
        if (previous != null) {
            previous.close();
        }
        InvertedIndex old = backend.open().apply(dir);                        // it may carry leftovers from another run
        old.clear();
        old.close();
        InvertedIndex index = backend.open().apply(dir);
        require(DatalakeStats.of(dir).files() == 0, backend.name() + ": clear dejó ficheros en " + dir);
        for (String term : queryTerms()) {
            require(index.postings(term).isEmpty(), backend.name() + ": clear dejó postings de \"" + term + "\"");
        }
        return index;
    }

    /**
     * The index must match an in-memory one built with the SAME TokenizedBook list:
     * posting lists of the query terms and of a few terms of the first and
     * last book, and the result of each AND query.
     */
    void verify(InvertedIndex index, List<TokenizedBook> books, String what) {
        InMemoryInvertedIndex reference = new InMemoryInvertedIndex();
        addAll(reference, books);

        Set<String> terms = new LinkedHashSet<>(queryTerms());
        books.get(0).terms().stream().sorted().limit(20).forEach(terms::add);
        books.get(books.size() - 1).terms().stream().sorted().limit(20).forEach(terms::add);
        for (String term : terms) {
            require(index.postings(term).equals(reference.postings(term)),
                    what + ": postings distintas para \"" + term + "\"");
        }
        SearchService actual = new SearchService(tokenizer, index);
        SearchService expected = new SearchService(tokenizer, reference);
        for (String q : queries) {
            require(actual.search(q).equals(expected.search(q)), what + ": resultado distinto para \"" + q + "\"");
        }
    }

    /** Empties the index (so no data is left between experiments) and closes it. */
    private static void discard(InvertedIndex index) {
        index.clear();
        index.close();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void addAll(InvertedIndex index, List<TokenizedBook> books) {
        for (TokenizedBook book : books) {
            index.addDocument(book.id(), book.terms());
        }
    }

    private Set<String> queryTerms() {
        Set<String> terms = new LinkedHashSet<>();
        queries.forEach(q -> terms.addAll(tokenizer.uniqueTerms(q)));
        return terms;
    }

    private static long distinctTerms(List<TokenizedBook> books) {
        Map<String, Boolean> seen = new HashMap<>();
        books.forEach(b -> b.terms().forEach(t -> seen.put(t, Boolean.TRUE)));
        return seen.size();
    }

    private Path dirFor(String experiment, Backend backend) {
        return workDir.resolve(experiment).resolve(backend.name());
    }

    private static Scenario scenario(String experiment, Backend backend, int n) {
        return new Scenario(LANGUAGE, experiment, backend.name(), n);
    }

    private static BenchmarkRow single(Backend backend, int n, String experiment, String metric,
                                       double value, String unit) {
        return new BenchmarkRow(LANGUAGE, experiment, backend.name(), n, 1, metric, value, unit);
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

    /** shared/queries.txt: one query per line; empty lines and those starting with '#' are ignored. */
    public static List<String> readQueries(Path file) {
        try {
            List<String> queries = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String q = line.strip();
                if (!q.isEmpty() && !q.startsWith("#")) {
                    queries.add(q);
                }
            }
            return queries;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudieron leer las consultas de " + file, e);
        }
    }

    // ------------------------------------------------------------------
    // Executable
    // ------------------------------------------------------------------

    /**
     * Usage:  IndexBenchmark [sizes] [book_datalake]
     *   sizes          separated by commas (default 50,100,200; about 10 min with mongo)
     *   book_datalake  real books already downloaded; without it, synthetic books (Zipf)
     * shared/, benchmarks/ and the Mongo address come from AppConfig. If Mongo does not answer, it is skipped.
     * Mongo uses the BENCH_DATABASE database, never the one of the real index.
     */
    public static void main(String[] args) {
        List<Integer> sizes = new ArrayList<>();
        for (String s : (args.length > 0 ? args[0] : "50,100,200").split(",")) {
            sizes.add(Integer.parseInt(s.strip()));
        }
        int max = sizes.stream().mapToInt(Integer::intValue).max().orElseThrow();
        AppConfig config = AppConfig.load();
        Tokenizer tokenizer = Tokenizer.fromStopwordsFile(config.stopwordsFile());
        List<String> queries = readQueries(config.queriesFile());

        List<RawBook> raw = args.length > 1
                ? BenchmarkBooks.fromDatalake(new BookBasedDatalake(Path.of(args[1])))
                : BenchmarkBooks.syntheticZipf(max, 5000, 30_000, 1);
        List<TokenizedBook> dataset = tokenizeAll(raw, tokenizer);         // BEFORE measuring anything
        raw = null;                                                          // the text is no longer needed

        List<Backend> backends = new ArrayList<>(fileBackends());
        String uri = config.mongoUri();
        if (mongoAvailable(uri)) {
            backends.add(mongoBackend(uri, BENCH_DATABASE, config.mongoCollection()));
        } else {
            System.out.println("AVISO: MongoDB no responde en " + uri + ": se mide sin mongo");
        }

        System.out.println("Libros tokenizados: " + dataset.size() + "  tamaños: " + sizes
                + "  backends: " + backends.stream().map(Backend::name).toList());
        IndexBenchmark benchmark = new IndexBenchmark(BenchmarkRunner.standard(), config.benchmarkWorkDir("index"),
                backends, tokenizer, queries, DEFAULT_QUERY_ROUNDS);
        Map<String, List<BenchmarkRow>> results = benchmark.runAll(dataset, sizes);
        writeResults(config.benchmarkResultsDir(), results);
        results.forEach((experiment, rows) -> System.out.println(experiment + ": " + rows.size() + " filas"));
    }
}
