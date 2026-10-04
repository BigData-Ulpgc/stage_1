package es.ulpgc.bigdata.config;

import es.ulpgc.bigdata.datamart.index.MongoInvertedIndex;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * ALL the program's configuration, and the ONLY place that knows where each thing lives:
 *
 *   <data>/datalake/<structure>/             DatalakeFactory
 *   <data>/datamarts/metadata.db             SQLite
 *   <data>/datamarts/inverted_index.json     monolithic index
 *   <data>/datamarts/inverted_index/         hierarchical index
 *   <data>/control/                          ControlFiles
 *   <shared>/book_ids.txt, stopwords.txt, queries.txt
 *   <sample>/book_ids.txt, raw/pg<ID>.txt   offline pipeline (sample_dataset/)
 *   <benchmarks>/results/, <benchmarks>/work/
 *
 * No other class writes "data/..." or "inverted_index.json": they ask for the path here.
 *
 * Where each value comes from (lower entries win over upper ones):
 *   1. this class's default values
 *   2. the .properties file (config.properties if it exists, or the one given with --config)
 *   3. the MONGO_URI environment variable (the one used by docker and the tests)
 *   4. the system properties: java -Dindex.structure=hierarchical ...
 */
public record AppConfig(Path dataDir, Path sharedDir, Path sampleDir, Path benchmarksDir,
                        String datalakeStructure, String indexStructure,
                        String mongoUri, String mongoDatabase, String mongoCollection,
                        Duration connectTimeout, Duration requestTimeout) {

    // --- Keys of the .properties file ---
    public static final String DATA_DIR = "data.dir";
    public static final String SHARED_DIR = "shared.dir";
    public static final String SAMPLE_DIR = "sample.dir";
    public static final String BENCHMARKS_DIR = "benchmarks.dir";
    public static final String DATALAKE_STRUCTURE = "datalake.structure";
    public static final String INDEX_STRUCTURE = "index.structure";
    public static final String MONGO_URI = "mongo.uri";
    public static final String MONGO_DATABASE = "mongo.database";
    public static final String MONGO_COLLECTION = "mongo.collection";
    public static final String CONNECT_TIMEOUT = "http.connect.timeout.seconds";
    public static final String REQUEST_TIMEOUT = "http.request.timeout.seconds";

    /** All the keys, in the order they are shown. */
    public static final List<String> KEYS = List.of(DATA_DIR, SHARED_DIR, SAMPLE_DIR, BENCHMARKS_DIR,
            DATALAKE_STRUCTURE, INDEX_STRUCTURE, MONGO_URI, MONGO_DATABASE, MONGO_COLLECTION,
            CONNECT_TIMEOUT, REQUEST_TIMEOUT);

    /** File that is read if no other is given and it exists in the current folder. */
    public static final Path DEFAULT_FILE = Path.of("config.properties");

    /** Default values: meant for running from java/stage1. */
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry(DATA_DIR, "data"),
            Map.entry(SHARED_DIR, "../../shared"),
            Map.entry(SAMPLE_DIR, "../../sample_dataset"),
            Map.entry(BENCHMARKS_DIR, "benchmarks"),
            Map.entry(DATALAKE_STRUCTURE, "time"),
            Map.entry(INDEX_STRUCTURE, "monolithic"),
            Map.entry(MONGO_URI, "mongodb://localhost:27017"),
            Map.entry(MONGO_DATABASE, MongoInvertedIndex.DEFAULT_DATABASE),
            Map.entry(MONGO_COLLECTION, MongoInvertedIndex.DEFAULT_COLLECTION),
            Map.entry(CONNECT_TIMEOUT, "10"),
            Map.entry(REQUEST_TIMEOUT, "15"));

    /** Checks the values on creation: a configuration error shows up at startup, not halfway. */
    public AppConfig {
        Objects.requireNonNull(dataDir, DATA_DIR);
        Objects.requireNonNull(sharedDir, SHARED_DIR);
        Objects.requireNonNull(sampleDir, SAMPLE_DIR);
        Objects.requireNonNull(benchmarksDir, BENCHMARKS_DIR);
        requireOneOf(DATALAKE_STRUCTURE, datalakeStructure, DatalakeFactory.NAMES);
        requireOneOf(INDEX_STRUCTURE, indexStructure, InvertedIndexFactory.NAMES);
        Objects.requireNonNull(mongoUri, MONGO_URI);
        Objects.requireNonNull(mongoDatabase, MONGO_DATABASE);
        Objects.requireNonNull(mongoCollection, MONGO_COLLECTION);
        requirePositive(CONNECT_TIMEOUT, connectTimeout);
        requirePositive(REQUEST_TIMEOUT, requestTimeout);
    }

    // ------------------------------------------------------------------
    // How it is created
    // ------------------------------------------------------------------

    /** Only the default values (no file, environment or -D): useful in tests. */
    public static AppConfig defaults() {
        return fromProperties(new Properties());
    }

    /** config.properties if it exists + MONGO_URI + -D. What the main methods use. */
    public static AppConfig load() {
        return load(Files.exists(DEFAULT_FILE) ? DEFAULT_FILE : null);
    }

    /** This file (null = none) + MONGO_URI + -D. */
    public static AppConfig load(Path file) {
        Properties merged = new Properties();
        if (file != null) {
            merged.putAll(readFile(file));
        }
        String envMongo = System.getenv("MONGO_URI");
        if (envMongo != null && !envMongo.isBlank()) {
            merged.setProperty(MONGO_URI, envMongo);
        }
        for (String key : KEYS) {
            String value = System.getProperty(key);
            if (value != null) {
                merged.setProperty(key, value);
            }
        }
        return fromProperties(merged);
    }

    /** Missing keys take the default value; unknown ones are an error (typos). */
    public static AppConfig fromProperties(Properties p) {
        for (String key : p.stringPropertyNames()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("Clave de configuración desconocida: " + key + ". Válidas: " + KEYS);
            }
        }
        return new AppConfig(
                Path.of(get(p, DATA_DIR)),
                Path.of(get(p, SHARED_DIR)),
                Path.of(get(p, SAMPLE_DIR)),
                Path.of(get(p, BENCHMARKS_DIR)),
                get(p, DATALAKE_STRUCTURE),
                get(p, INDEX_STRUCTURE),
                get(p, MONGO_URI),
                get(p, MONGO_DATABASE),
                get(p, MONGO_COLLECTION),
                seconds(p, CONNECT_TIMEOUT),
                seconds(p, REQUEST_TIMEOUT));
    }

    /** The same configuration with another data folder (the benchmarks work in their own folder). */
    public AppConfig withDataDir(Path newDataDir) {
        return new AppConfig(newDataDir, sharedDir, sampleDir, benchmarksDir, datalakeStructure, indexStructure,
                mongoUri, mongoDatabase, mongoCollection, connectTimeout, requestTimeout);
    }

    // ------------------------------------------------------------------
    // Paths: the only place where they are built
    // ------------------------------------------------------------------

    /** <data>/datalake/<structure>: each structure in its own folder, so they can coexist. */
    public Path datalakeDir(String structure) {
        return dataDir.resolve("datalake").resolve(structure);
    }

    /** The folder of the active structure. */
    public Path datalakeDir() {
        return datalakeDir(datalakeStructure);
    }

    public Path datamartsDir() {
        return dataDir.resolve("datamarts");
    }

    public Path metadataDb() {
        return datamartsDir().resolve("metadata.db");
    }

    public Path monolithicIndexFile() {
        return datamartsDir().resolve("inverted_index.json");
    }

    public Path hierarchicalIndexDir() {
        return datamartsDir().resolve("inverted_index");
    }

    public Path controlDir() {
        return dataDir.resolve("control");
    }

    public Path bookIdsFile() {
        return sharedDir.resolve("book_ids.txt");
    }

    public Path stopwordsFile() {
        return sharedDir.resolve("stopwords.txt");
    }

    public Path queriesFile() {
        return sharedDir.resolve("queries.txt");
    }

    /** The 15 ids of the sample dataset: the offline pipeline downloads these instead of bookIdsFile(). */
    public Path sampleBookIdsFile() {
        return sampleDir.resolve("book_ids.txt");
    }

    /** The raw Gutenberg files of the sample, <sample>/raw/pg<ID>.txt: the offline BookSource. */
    public Path sampleRawDir() {
        return sampleDir.resolve("raw");
    }

    public Path benchmarkResultsDir() {
        return benchmarksDir.resolve("results");
    }

    /** Work folder of a benchmark: <benchmarks>/work/<name>. */
    public Path benchmarkWorkDir(String benchmark) {
        return benchmarksDir.resolve("work").resolve(benchmark);
    }

    /** One line per key, with the effective value (Main's "config" command). */
    public String describe() {
        return String.join("\n",
                DATA_DIR + " = " + dataDir,
                SHARED_DIR + " = " + sharedDir,
                SAMPLE_DIR + " = " + sampleDir,
                BENCHMARKS_DIR + " = " + benchmarksDir,
                DATALAKE_STRUCTURE + " = " + datalakeStructure + "   -> " + datalakeDir(),
                INDEX_STRUCTURE + " = " + indexStructure,
                MONGO_URI + " = " + mongoUri,
                MONGO_DATABASE + " = " + mongoDatabase,
                MONGO_COLLECTION + " = " + mongoCollection,
                CONNECT_TIMEOUT + " = " + connectTimeout.toSeconds(),
                REQUEST_TIMEOUT + " = " + requestTimeout.toSeconds());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Properties readFile(Path file) {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(reader);
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la configuración " + file, e);
        }
    }

    private static String get(Properties p, String key) {
        return p.getProperty(key, DEFAULTS.get(key)).strip();
    }

    private static Duration seconds(Properties p, String key) {
        String value = get(p, key);
        try {
            return Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " debe ser un número de segundos: \"" + value + "\"");
        }
    }

    private static void requireOneOf(String key, String value, List<String> valid) {
        if (!valid.contains(value)) {
            throw new IllegalArgumentException(key + " = \"" + value + "\" no existe. Opciones: " + valid);
        }
    }

    private static void requirePositive(String key, Duration value) {
        Objects.requireNonNull(value, key);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(key + " debe ser mayor que 0: " + value.toSeconds());
        }
    }
}
