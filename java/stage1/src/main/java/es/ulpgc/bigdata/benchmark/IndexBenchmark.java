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
 * Benchmark de los tres índices invertidos (monolithic, hierarchical, mongo), con los
 * experimentos de la sección 9 del SPEC:
 *
 *   index_build    índice vacío -> N libros añadidos + flush           elapsed (ms) + throughput (books/s)
 *   index_query    la carga de queries.txt (AND) sobre el índice en disco elapsed (ms) + per_query (µs)
 *   index_update   índice con N-k libros -> añadir k, flush por libro   elapsed (ms) + per_book (ms)
 *   index_memory   heap de Java con el índice construido / reabierto     heap_after_build, heap_after_open (bytes)
 *   index_disk     ocupación tras construir                              bytes (+ files, allocated_bytes) + terms, postings
 *
 * Reglas para que la comparación sea justa:
 *  - Los libros se tokenizan UNA vez, antes de todo (tokenizeAll). Los tres backends reciben
 *    la MISMA lista de TokenizedBook: el tokenizador no está en ninguna medida.
 *  - Antes de cada repetición, freshIndex deja el backend vacío (clear) y comprueba que lo está.
 *  - Después de medir, se comprueba que el índice da los mismos resultados que un índice
 *    en memoria construido con los mismos términos (verify). Si no, el benchmark falla.
 *  - Cada tamaño N usa los N primeros libros del dataset: los tamaños son prefijos del mismo dataset.
 */
public class IndexBenchmark {

    public static final String LANGUAGE = "java";

    /** Veces que se repite la carga de queries.txt dentro de una medida (10 consultas solas duran µs). */
    public static final int DEFAULT_QUERY_ROUNDS = 100;

    /** Base de Mongo del benchmark: nunca la del índice real (search_engine). */
    public static final String BENCH_DATABASE = "search_engine_bench";

    /** Un libro ya tokenizado: lo único que reciben los índices. */
    public record TokenizedBook(int id, Set<String> terms) {
        public TokenizedBook {
            terms = Set.copyOf(terms);                         // inmodificable: nadie lo cambia entre backends
        }
    }

    /**
     * Un backend a comparar: su nombre en el CSV y cómo abrir el índice que vive en una carpeta.
     * Abrir dos veces la misma carpeta debe dar el mismo índice (así se "reabre" tras un flush).
     */
    public record Backend(String name, Function<Path, InvertedIndex> open) {
    }

    /**
     * monolithic y hierarchical, creados por InvertedIndexFactory con la carpeta de datos
     * apuntando a 'dir': las rutas de dentro (datamarts/inverted_index.json...) las decide AppConfig.
     */
    public static List<Backend> fileBackends() {
        AppConfig base = AppConfig.defaults();
        return List.of("monolithic", "hierarchical").stream()
                .map(name -> new Backend(name, dir -> InvertedIndexFactory.create(name, base.withDataDir(dir))))
                .toList();
    }

    /** Mongo no usa la carpeta: siempre la misma colección, que freshIndex vacía. */
    public static Backend mongoBackend(String uri, String database, String collection) {
        return new Backend("mongo", dir -> new MongoInvertedIndex(uri, database, collection));
    }

    /** ¿Responde Mongo en esta dirección? (para saltarlo en vez de fallar si no está arrancado) */
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

    /** Tokeniza todos los libros. Se llama ANTES de cualquier medida. */
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

    /** Los cinco experimentos para cada tamaño (prefijos de 'dataset'). */
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
    // index_build: de índice vacío a índice persistido con N libros
    // ------------------------------------------------------------------

    /**
     * Se mide: N addDocument con términos ya calculados + UN flush final (hasta que está en disco).
     * No se mide: leer libros, tokenizar, vaciar el índice ni abrir la conexión.
     */
    public List<BenchmarkRow> build(List<TokenizedBook> books) {
        List<BenchmarkRow> rows = new ArrayList<>();
        for (Backend backend : backends) {
            Path dir = dirFor("build", backend);
            InvertedIndex[] index = new InvertedIndex[1];
            List<BenchmarkRow> elapsed = runner.run(scenario("index_build", backend, books.size()),
                    () -> index[0] = freshIndex(backend, dir, index[0]),      // setup: índice vacío
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
    // index_query: la misma carga AND para todos
    // ------------------------------------------------------------------

    /**
     * El índice se construye y se REABRE antes de medir: así se consulta lo que hay en disco
     * (para monolithic, el JSON ya cargado en memoria al abrir; para los otros, sus ficheros o Mongo).
     * Se mide: queryRounds veces la carga de queries.txt con SearchService (incluye tokenizar la consulta).
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
            verify(index, books, backend.name() + " query");                  // mismos resultados que la referencia
            SearchService search = new SearchService(tokenizer, index);
            long[] found = {0};
            List<BenchmarkRow> elapsed = runner.run(scenario("index_query", backend, books.size()),
                    () -> found[0] = 0,
                    () -> {
                        for (int round = 0; round < queryRounds; round++) {
                            for (String q : queries) {
                                found[0] += search.search(q).size();          // usar el resultado
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
    // index_update: añadir k libros a un índice que ya tiene N-k
    // ------------------------------------------------------------------

    /**
     * k = 10 % de N (mínimo 1), como datalake_incremental.
     * Setup (sin medir): índice vacío, N-k libros, flush, cerrar y reabrir (como lo encontraría
     * una ejecución posterior del pipeline).
     * Se mide: los k libros, cada uno con addDocument + flush, igual que hace Indexer (reto 21).
     * Aquí monolithic reescribe el JSON COMPLETO en cada flush; hierarchical sólo los
     * ficheros de los términos del libro; mongo sólo esos documentos.
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
                    () -> {                                                   // setup, sin medir
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
            verify(index[0], books, backend.name() + " update");              // N-k + k == construir N
            discard(index[0]);
            rows.addAll(elapsed);
            for (BenchmarkRow e : elapsed) {
                rows.add(derived(e, "per_book", "ms", e.value() / k));
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // index_memory: heap de Java (estimación)
    // ------------------------------------------------------------------

    /**
     * Heap usado (tras pedir GC) antes y después, con el índice todavía vivo:
     *   heap_after_build  índice construido con N libros y flush hecho
     *   heap_after_open   índice recién abierto desde disco (lo que ocupa un buscador en marcha)
     *
     * Es una ESTIMACIÓN: System.gc() es una petición, no una orden, y puede salir algún valor
     * negativo o con ruido de unos KB.
     *
     * Limitación de Mongo: aquí sólo se ve el heap del CLIENTE (driver, conexiones, lo pendiente).
     * Los datos viven en otro proceso, mongod, con su propia caché (WiredTiger), que no está en
     * nuestro heap y además es compartida por todas las colecciones: no es comparable con el heap.
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
            Reference.reachabilityFence(index);                               // que el GC no lo dé por muerto antes
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

    /** Heap ocupado tras un par de GC (lo que queda es lo que sigue vivo). */
    static long usedHeapAfterGc() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    // ------------------------------------------------------------------
    // index_disk: bytes en disco tras construir
    // ------------------------------------------------------------------

    /**
     * bytes            diskUsageBytes() del contrato (Mongo: storageSize + totalIndexSize, comprimido)
     * files            ficheros en la carpeta (sólo backends de ficheros)
     * allocated_bytes  bloques que reserva el disco (sólo backends de ficheros): con miles de
     *                  ficheros pequeños, hierarchical ocupa mucho más de lo que suman sus bytes
     * terms, postings  tamaño del índice lógico: IGUAL en todos los backends (mismos términos)
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
            if (Files.isDirectory(dir)) {                                     // Mongo no escribe en la carpeta
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
    // Estado inicial equivalente y comprobación de resultados
    // ------------------------------------------------------------------

    /**
     * Cierra el índice anterior, borra lo que hubiera (clear) y abre uno nuevo.
     * Comprueba que el nuevo está vacío de verdad: ningún fichero en la carpeta y ninguna
     * posting list para los términos de las consultas. Siempre en el setup, nunca medido.
     */
    InvertedIndex freshIndex(Backend backend, Path dir, InvertedIndex previous) {
        if (previous != null) {
            previous.close();
        }
        InvertedIndex old = backend.open().apply(dir);                        // puede traer restos de otra ejecución
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
     * El índice debe coincidir con uno en memoria construido con los MISMOS TokenizedBook:
     * posting lists de los términos de las consultas y de unos cuantos términos del primer y
     * del último libro, y resultado de cada consulta AND.
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

    /** Vacía el índice (para no dejar datos entre experimentos) y lo cierra. */
    private static void discard(InvertedIndex index) {
        index.clear();
        index.close();
    }

    // ------------------------------------------------------------------
    // Auxiliares
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

    /** shared/queries.txt: una consulta por línea; se ignoran vacías y las que empiezan por '#'. */
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
    // Ejecutable
    // ------------------------------------------------------------------

    /**
     * Uso:  IndexBenchmark [tamaños] [datalake_book]
     *   tamaños        separados por comas (por defecto 50,100,200; unos 10 min con mongo)
     *   datalake_book  libros reales ya descargados; sin él, libros sintéticos (Zipf)
     * shared/, benchmarks/ y la dirección de Mongo salen de AppConfig. Si Mongo no responde, se salta.
     * Mongo usa la base BENCH_DATABASE, nunca la del índice real.
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
        List<TokenizedBook> dataset = tokenizeAll(raw, tokenizer);         // ANTES de medir nada
        raw = null;                                                          // el texto ya no hace falta

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
