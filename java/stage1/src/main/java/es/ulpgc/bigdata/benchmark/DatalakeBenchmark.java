package es.ulpgc.bigdata.benchmark;

import es.ulpgc.bigdata.benchmark.BenchmarkRunner.Scenario;
import es.ulpgc.bigdata.config.AppConfig;
import es.ulpgc.bigdata.config.DatalakeFactory;
import es.ulpgc.bigdata.datalake.BookBasedDatalake;
import es.ulpgc.bigdata.datalake.Datalake;
import es.ulpgc.bigdata.datalake.DatalakeStats;
import es.ulpgc.bigdata.model.BookLocation;
import es.ulpgc.bigdata.model.RawBook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Benchmark of the three datalake structures (book, range, time), with the
 * experiments of section 9 of the SPEC:
 *
 *   datalake_write        save all the books                   elapsed (ms) + throughput (books/s)
 *   datalake_lookup       locate every id                      elapsed (ms) + per_lookup (µs)
 *   datalake_incremental  detect the 10 % of new books         elapsed (ms) + detected (books)
 *   datalake_recovery     repair a simulated crash             elapsed (ms) + recovered/lost/duplicates
 *   datalake_storage      disk usage after saving everything   files, directories, bytes...
 *
 * Every structure receives EXACTLY the same list of books, in the same order.
 * Preparing and cleaning always go in the runner's setup: never inside the measured time.
 */
public class DatalakeBenchmark {

    public static final String LANGUAGE = "java";

    /** A structure to compare: its name in the CSV and how to create an empty datalake in a folder. */
    public record Structure(String name, Function<Path, Datalake> factory) {
    }

    /**
     * Every structure of DatalakeFactory. "time" receives a new simulated clock (10 books per hour)
     * on each creation, so every repetition is identical; the others ignore it.
     */
    public static List<Structure> defaultStructures() {
        return DatalakeFactory.NAMES.stream()
                .map(name -> new Structure(name, dir -> DatalakeFactory.create(name, dir, SimulatedClock.tenBooksPerHour())))
                .toList();
    }

    private final BenchmarkRunner runner;
    private final Path workDir;
    private final List<Structure> structures;

    public DatalakeBenchmark(BenchmarkRunner runner, Path workDir, List<Structure> structures) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.workDir = Objects.requireNonNull(workDir, "workDir");
        this.structures = List.copyOf(structures);
    }

    /** The five experiments. Key = experiment name, value = its CSV rows. */
    public Map<String, List<BenchmarkRow>> runAll(List<RawBook> books) {
        Map<String, List<BenchmarkRow>> results = new LinkedHashMap<>();
        results.put("datalake_write", write(books));
        results.put("datalake_lookup", lookup(books));
        results.put("datalake_incremental", incremental(books));
        results.put("datalake_recovery", recovery(books));
        results.put("datalake_storage", storage(books));
        return results;
    }

    /** Writes benchmarks/results/java_<experiment>.csv for each experiment. */
    public static void writeResults(Path resultsDir, Map<String, List<BenchmarkRow>> results) {
        results.forEach((experiment, rows) ->
                CsvResults.write(CsvResults.fileFor(resultsDir, LANGUAGE, experiment), rows));
    }

    // ------------------------------------------------------------------
    // 1. Write: save all the books into an empty datalake
    // ------------------------------------------------------------------

    public List<BenchmarkRow> write(List<RawBook> books) {
        List<BenchmarkRow> rows = new ArrayList<>();
        for (Structure s : structures) {
            Path dir = dirFor("write", s);
            Datalake[] datalake = new Datalake[1];
            List<BenchmarkRow> elapsed = runner.run(scenario("datalake_write", s, books),
                    () -> datalake[0] = freshDatalake(s, dir),                  // setup: empty folder
                    () -> {
                        for (RawBook book : books) {
                            datalake[0].save(book);
                        }
                    });
            rows.addAll(elapsed);
            rows.addAll(derived(elapsed, "throughput", "books_per_s",
                    ms -> books.size() / (ms / 1000.0)));
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // 2. Lookup: locate every id, in a fixed "random" order
    // ------------------------------------------------------------------

    public List<BenchmarkRow> lookup(List<RawBook> books) {
        List<Integer> ids = shuffledIds(books);                                 // same order for all of them
        List<BenchmarkRow> rows = new ArrayList<>();
        for (Structure s : structures) {
            Path dir = dirFor("lookup", s);
            Datalake datalake = freshDatalake(s, dir);
            books.forEach(datalake::save);                                      // one-off preparation, not measured
            int[] found = {0};
            List<BenchmarkRow> elapsed = runner.run(scenario("datalake_lookup", s, books),
                    () -> found[0] = 0,
                    () -> {
                        for (int id : ids) {
                            if (datalake.locate(id).isPresent()) {
                                found[0]++;                                     // use the result
                            }
                        }
                    });
            require(found[0] == ids.size(), s.name() + ": locate no encontró todos los libros");
            rows.addAll(elapsed);
            rows.addAll(derived(elapsed, "per_lookup", "us", ms -> ms * 1000.0 / ids.size()));
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // 3. Incremental: which books are new since last time?
    // ------------------------------------------------------------------

    /**
     * Common definition: the datalake holds 90 % of already known books and the remaining
     * 10 % is added. What is measured is listBookIds() minus the known ids. It is the operation
     * the Datalake contract allows doing the same way in all three structures.
     */
    public List<BenchmarkRow> incremental(List<RawBook> books) {
        require(books.size() >= 2, "hacen falta al menos 2 libros");
        int newCount = Math.max(1, books.size() / 10);
        List<RawBook> known = books.subList(0, books.size() - newCount);
        List<RawBook> fresh = books.subList(books.size() - newCount, books.size());
        Set<Integer> knownIds = idsOf(known);

        List<BenchmarkRow> rows = new ArrayList<>();
        for (Structure s : structures) {
            Path dir = dirFor("incremental", s);
            Datalake[] datalake = new Datalake[1];
            AtomicReference<Set<Integer>> detected = new AtomicReference<>(Set.of());
            List<BenchmarkRow> elapsed = runner.run(scenario("datalake_incremental", s, books),
                    () -> {                                                      // setup, not measured
                        datalake[0] = freshDatalake(s, dir);
                        known.forEach(datalake[0]::save);
                        fresh.forEach(datalake[0]::save);                        // the new ones "arrive"
                    },
                    () -> {
                        Set<Integer> all = new TreeSet<>(datalake[0].listBookIds());
                        all.removeAll(knownIds);
                        detected.set(all);
                    });
            require(detected.get().equals(idsOf(fresh)), s.name() + ": no detectó exactamente los libros nuevos");
            rows.addAll(elapsed);
            rows.addAll(derived(elapsed, "detected", "books", ms -> detected.get().size()));
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // 4. Recovery: a simulated crash and its repair
    // ------------------------------------------------------------------

    /**
     * Controlled and reproducible failure: after saving everything, the save of 1 in every 10
     * books is "cut" right before moving the body (body.txt -> body.txt.tmp), which is
     * what a process that died at that instant would leave behind (challenge 11).
     * Recovery = detect the missing ones with listBookIds() and save them again.
     */
    public List<BenchmarkRow> recovery(List<RawBook> books) {
        List<RawBook> damaged = everyTenth(books);
        Set<Integer> expected = idsOf(books);

        List<BenchmarkRow> rows = new ArrayList<>();
        for (Structure s : structures) {
            Path dir = dirFor("recovery", s);
            Datalake[] datalake = new Datalake[1];
            int[] recovered = {0};
            List<BenchmarkRow> elapsed = runner.run(scenario("datalake_recovery", s, books),
                    () -> {                                                      // setup, not measured
                        datalake[0] = freshDatalake(s, dir);
                        books.forEach(datalake[0]::save);
                        for (RawBook book : damaged) {
                            interruptBeforeBodyMove(datalake[0].locate(book.id()).orElseThrow());
                        }
                        recovered[0] = 0;
                    },
                    () -> {
                        Set<Integer> present = new HashSet<>(datalake[0].listBookIds());
                        for (RawBook book : books) {
                            if (!present.contains(book.id())) {
                                datalake[0].save(book);
                                recovered[0]++;
                            }
                        }
                    });
            // Check on the final state (all repetitions are identical).
            List<Integer> listed = datalake[0].listBookIds();
            long lost = expected.stream().filter(id -> !listed.contains(id)).count();
            long duplicates = countFiles(dir, "body.txt") - books.size();          // extra complete bodies
            require(recovered[0] == damaged.size(), s.name() + ": no recuperó todos los libros dañados");
            require(lost == 0 && duplicates == 0, s.name() + ": recovery dejó pérdidas o duplicados");

            rows.addAll(elapsed);
            int dataset = books.size();
            rows.add(single("datalake_recovery", s, dataset, "recovered", recovered[0], "books"));
            rows.add(single("datalake_recovery", s, dataset, "lost", lost, "books"));
            rows.add(single("datalake_recovery", s, dataset, "duplicates", duplicates, "books"));
        }
        return rows;
    }

    /** Simulates that the process died after writing the body to .tmp and before moving it. */
    static void interruptBeforeBodyMove(BookLocation location) {
        try {
            Files.move(location.bodyPath(), Path.of(location.bodyPath() + ".tmp"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------
    // 5. Storage: files, folders and bytes
    // ------------------------------------------------------------------

    public List<BenchmarkRow> storage(List<RawBook> books) {
        List<BenchmarkRow> rows = new ArrayList<>();
        for (Structure s : structures) {
            Path dir = dirFor("storage", s);
            Datalake datalake = freshDatalake(s, dir);
            books.forEach(datalake::save);

            DatalakeStats stats = DatalakeStats.of(dir);
            int n = books.size();
            rows.add(single("datalake_storage", s, n, "files", stats.files(), "count"));
            rows.add(single("datalake_storage", s, n, "directories", stats.directories(), "count"));
            rows.add(single("datalake_storage", s, n, "max_entries_per_dir", stats.maxEntriesInDir(), "count"));
            rows.add(single("datalake_storage", s, n, "bytes", stats.bytes(), "bytes"));
            rows.add(single("datalake_storage", s, n, "allocated_bytes", allocatedBytes(dir), "bytes"));
        }
        return rows;
    }

    /**
     * Space the disk actually reserves: every file and folder takes whole blocks
     * (usually 4 KB) even if it has 3 bytes. It is an estimate: it does not include
     * the file system's internal metadata.
     */
    static long allocatedBytes(Path root) {
        try (Stream<Path> all = Files.walk(root)) {
            long block = Files.getFileStore(root).getBlockSize();
            long total = 0;
            for (Path p : (Iterable<Path>) all::iterator) {
                long size = Files.isDirectory(p) ? 1 : Files.size(p);
                total += ((size + block - 1) / block) * block;                  // round up to blocks
            }
            return total;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo medir " + root, e);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Path dirFor(String experiment, Structure s) {
        return workDir.resolve(experiment).resolve(s.name());
    }

    /** Deletes the folder and creates an empty datalake in it (always inside a setup). */
    private static Datalake freshDatalake(Structure s, Path dir) {
        deleteRecursively(dir);
        return s.factory().apply(dir);
    }

    static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo borrar " + dir, e);
        }
    }

    private static Scenario scenario(String experiment, Structure s, List<RawBook> books) {
        return new Scenario(LANGUAGE, experiment, s.name(), books.size());
    }

    private static BenchmarkRow single(String experiment, Structure s, int size, String metric,
                                       double value, String unit) {
        return new BenchmarkRow(LANGUAGE, experiment, s.name(), size, 1, metric, value, unit);
    }

    /** A metric computed from each "elapsed" row, with the same repetition. */
    private static List<BenchmarkRow> derived(List<BenchmarkRow> elapsed, String metric, String unit,
                                              DoubleUnaryOperator fromMillis) {
        List<BenchmarkRow> rows = new ArrayList<>();
        for (BenchmarkRow e : elapsed) {
            double ms = Math.max(e.value(), 0.001);                              // avoids dividing by 0
            rows.add(new BenchmarkRow(e.language(), e.experiment(), e.structure(), e.datasetSize(),
                    e.repetition(), metric, fromMillis.applyAsDouble(ms), unit));
        }
        return rows;
    }

    private static List<Integer> shuffledIds(List<RawBook> books) {
        List<Integer> ids = new ArrayList<>(idsOf(books));
        Collections.shuffle(ids, new Random(42));                               // fixed seed
        return ids;
    }

    private static List<RawBook> everyTenth(List<RawBook> books) {
        List<RawBook> chosen = new ArrayList<>();
        for (int i = 0; i < books.size(); i += 10) {
            chosen.add(books.get(i));
        }
        return chosen;
    }

    private static Set<Integer> idsOf(List<RawBook> books) {
        Set<Integer> ids = new TreeSet<>();
        books.forEach(b -> ids.add(b.id()));
        return ids;
    }

    private static long countFiles(Path root, String suffix) {
        try (Stream<Path> all = Files.walk(root)) {
            return all.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(suffix)).count();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo contar en " + root, e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    // ------------------------------------------------------------------
    // Executable
    // ------------------------------------------------------------------

    /**
     * Usage:  DatalakeBenchmark [book_datalake_with_the_books]
     *   e.g. data/datalake/book  (what the pipeline left, or sample_dataset/ in book structure)
     * Without arguments it uses 200 synthetic books of 300 KB.
     * Work and results folders: AppConfig (benchmarks.dir).
     */
    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        List<RawBook> books = args.length > 0
                ? BenchmarkBooks.fromDatalake(new BookBasedDatalake(Path.of(args[0])))
                : BenchmarkBooks.synthetic(200, 300, 1);
        Path work = config.benchmarkWorkDir("datalake");
        Path results = config.benchmarkResultsDir();

        System.out.println("Libros: " + books.size());
        DatalakeBenchmark benchmark = new DatalakeBenchmark(BenchmarkRunner.standard(), work, defaultStructures());
        Map<String, List<BenchmarkRow>> all = benchmark.runAll(books);
        writeResults(results, all);
        all.forEach((experiment, rows) -> System.out.println(experiment + ": " + rows.size() + " filas"));
    }
}