package es.ulpgc.bigdata.benchmark;

import es.ulpgc.bigdata.benchmark.BenchmarkRunner.Scenario;
import es.ulpgc.bigdata.datalake.BookBasedDatalake;
import es.ulpgc.bigdata.datalake.Datalake;
import es.ulpgc.bigdata.datalake.DatalakeStats;
import es.ulpgc.bigdata.datalake.RangeBasedDatalake;
import es.ulpgc.bigdata.datalake.TimeBasedDatalake;
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
 * Benchmark de las tres estructuras del datalake (book, range, time), con los
 * experimentos de la sección 9 del SPEC:
 *
 *   datalake_write        guardar todos los libros             elapsed (ms) + throughput (books/s)
 *   datalake_lookup       locate de todos los ids              elapsed (ms) + per_lookup (µs)
 *   datalake_incremental  detectar el 10 % de libros nuevos    elapsed (ms) + detected (books)
 *   datalake_recovery     reparar un corte simulado            elapsed (ms) + recovered/lost/duplicates
 *   datalake_storage      ocupación tras guardar todo          files, directories, bytes...
 *
 * Todas las estructuras reciben EXACTAMENTE la misma lista de libros, en el mismo orden.
 * Preparar y limpiar va siempre en el setup del runner: nunca dentro del tiempo medido.
 */
public class DatalakeBenchmark {

    public static final String LANGUAGE = "java";

    /** Una estructura a comparar: su nombre en el CSV y cómo crear un datalake vacío en una carpeta. */
    public record Structure(String name, Function<Path, Datalake> factory) {
    }

    /** book, range y time; time con un reloj simulado (10 libros por hora), igual en cada repetición. */
    public static List<Structure> defaultStructures() {
        return List.of(
                new Structure("book", BookBasedDatalake::new),
                new Structure("range", RangeBasedDatalake::new),
                new Structure("time", dir -> new TimeBasedDatalake(dir, SimulatedClock.tenBooksPerHour())));
    }

    private final BenchmarkRunner runner;
    private final Path workDir;
    private final List<Structure> structures;

    public DatalakeBenchmark(BenchmarkRunner runner, Path workDir, List<Structure> structures) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.workDir = Objects.requireNonNull(workDir, "workDir");
        this.structures = List.copyOf(structures);
    }

    /** Los cinco experimentos. Clave = nombre del experimento, valor = sus filas CSV. */
    public Map<String, List<BenchmarkRow>> runAll(List<RawBook> books) {
        Map<String, List<BenchmarkRow>> results = new LinkedHashMap<>();
        results.put("datalake_write", write(books));
        results.put("datalake_lookup", lookup(books));
        results.put("datalake_incremental", incremental(books));
        results.put("datalake_recovery", recovery(books));
        results.put("datalake_storage", storage(books));
        return results;
    }

    /** Escribe benchmarks/results/java_<experimento>.csv por cada experimento. */
    public static void writeResults(Path resultsDir, Map<String, List<BenchmarkRow>> results) {
        results.forEach((experiment, rows) ->
                CsvResults.write(CsvResults.fileFor(resultsDir, LANGUAGE, experiment), rows));
    }

    // ------------------------------------------------------------------
    // 1. Escritura: guardar todos los libros en un datalake vacío
    // ------------------------------------------------------------------

    public List<BenchmarkRow> write(List<RawBook> books) {
        List<BenchmarkRow> rows = new ArrayList<>();
        for (Structure s : structures) {
            Path dir = dirFor("write", s);
            Datalake[] datalake = new Datalake[1];
            List<BenchmarkRow> elapsed = runner.run(scenario("datalake_write", s, books),
                    () -> datalake[0] = freshDatalake(s, dir),                  // setup: carpeta vacía
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
    // 2. Lookup: locate de todos los ids, en un orden fijo "aleatorio"
    // ------------------------------------------------------------------

    public List<BenchmarkRow> lookup(List<RawBook> books) {
        List<Integer> ids = shuffledIds(books);                                 // mismo orden para todas
        List<BenchmarkRow> rows = new ArrayList<>();
        for (Structure s : structures) {
            Path dir = dirFor("lookup", s);
            Datalake datalake = freshDatalake(s, dir);
            books.forEach(datalake::save);                                      // preparación única, sin medir
            int[] found = {0};
            List<BenchmarkRow> elapsed = runner.run(scenario("datalake_lookup", s, books),
                    () -> found[0] = 0,
                    () -> {
                        for (int id : ids) {
                            if (datalake.locate(id).isPresent()) {
                                found[0]++;                                     // usar el resultado
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
    // 3. Incremental: ¿qué libros son nuevos desde la última vez?
    // ------------------------------------------------------------------

    /**
     * Definición común: el datalake tiene un 90 % de libros ya conocidos y se añade el
     * 10 % restante. Se mide listBookIds() menos los ids conocidos. Es la operación que
     * el contrato Datalake permite hacer igual en las tres estructuras.
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
                    () -> {                                                      // setup, sin medir
                        datalake[0] = freshDatalake(s, dir);
                        known.forEach(datalake[0]::save);
                        fresh.forEach(datalake[0]::save);                        // "llegan" los nuevos
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
    // 4. Recovery: un corte simulado y su reparación
    // ------------------------------------------------------------------

    /**
     * Fallo controlado y reproducible: tras guardar todo, a 1 de cada 10 libros se le
     * "corta" el guardado justo antes de mover el body (body.txt -> body.txt.tmp), que es
     * lo que dejaría un proceso muerto en ese instante (reto 11).
     * Recovery = detectar los que faltan con listBookIds() y volver a guardarlos.
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
                    () -> {                                                      // setup, sin medir
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
            // Comprobación sobre el estado final (todas las repeticiones son idénticas).
            List<Integer> listed = datalake[0].listBookIds();
            long lost = expected.stream().filter(id -> !listed.contains(id)).count();
            long duplicates = countFiles(dir, "body.txt") - books.size();          // bodies completos de más
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

    /** Simula que el proceso murió después de escribir el body en .tmp y antes de moverlo. */
    static void interruptBeforeBodyMove(BookLocation location) {
        try {
            Files.move(location.bodyPath(), Path.of(location.bodyPath() + ".tmp"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------
    // 5. Almacenamiento: ficheros, carpetas y bytes
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
     * Espacio que el disco reserva de verdad: cada fichero y carpeta ocupa bloques
     * enteros (normalmente 4 KB) aunque tenga 3 bytes. Es una estimación: no incluye
     * los metadatos internos del sistema de ficheros.
     */
    static long allocatedBytes(Path root) {
        try (Stream<Path> all = Files.walk(root)) {
            long block = Files.getFileStore(root).getBlockSize();
            long total = 0;
            for (Path p : (Iterable<Path>) all::iterator) {
                long size = Files.isDirectory(p) ? 1 : Files.size(p);
                total += ((size + block - 1) / block) * block;                  // redondear a bloques
            }
            return total;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo medir " + root, e);
        }
    }

    // ------------------------------------------------------------------
    // Auxiliares
    // ------------------------------------------------------------------

    private Path dirFor(String experiment, Structure s) {
        return workDir.resolve(experiment).resolve(s.name());
    }

    /** Borra la carpeta y crea un datalake vacío en ella (siempre dentro de un setup). */
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

    /** Una métrica calculada a partir de cada fila "elapsed", con la misma repetición. */
    private static List<BenchmarkRow> derived(List<BenchmarkRow> elapsed, String metric, String unit,
                                              DoubleUnaryOperator fromMillis) {
        List<BenchmarkRow> rows = new ArrayList<>();
        for (BenchmarkRow e : elapsed) {
            double ms = Math.max(e.value(), 0.001);                              // evita dividir entre 0
            rows.add(new BenchmarkRow(e.language(), e.experiment(), e.structure(), e.datasetSize(),
                    e.repetition(), metric, fromMillis.applyAsDouble(ms), unit));
        }
        return rows;
    }

    private static List<Integer> shuffledIds(List<RawBook> books) {
        List<Integer> ids = new ArrayList<>(idsOf(books));
        Collections.shuffle(ids, new Random(42));                               // semilla fija
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
    // Ejecutable
    // ------------------------------------------------------------------

    /**
     * Uso:  DatalakeBenchmark <datalake_book_con_los_libros> [carpeta_trabajo] [carpeta_resultados]
     *   p. ej. data/datalake/book  (lo que dejó el pipeline, o sample_dataset/ en estructura book)
     * Sin argumentos usa 200 libros sintéticos de 300 KB.
     */
    public static void main(String[] args) {
        List<RawBook> books = args.length > 0
                ? BenchmarkBooks.fromDatalake(new BookBasedDatalake(Path.of(args[0])))
                : BenchmarkBooks.synthetic(200, 300, 1);
        Path work = Path.of(args.length > 1 ? args[1] : "benchmarks/work/datalake");
        Path results = Path.of(args.length > 2 ? args[2] : "benchmarks/results");

        System.out.println("Libros: " + books.size());
        DatalakeBenchmark benchmark = new DatalakeBenchmark(BenchmarkRunner.standard(), work, defaultStructures());
        Map<String, List<BenchmarkRow>> all = benchmark.runAll(books);
        writeResults(results, all);
        all.forEach((experiment, rows) -> System.out.println(experiment + ": " + rows.size() + " filas"));
    }
}