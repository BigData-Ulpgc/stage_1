package es.ulpgc.bigdata.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Result files: benchmarks/results/<language>_<experiment>.csv (SPEC, section 9).
 *
 * Each experiment is written WHOLE at once, with temp file + move: the file
 * always contains a complete run. If the benchmark fails halfway, nothing is
 * written and the previous results stay intact.
 */
public final class CsvResults {

    private CsvResults() {
    }

    /** benchmarks/results + "java" + "index_build" -> benchmarks/results/java_index_build.csv */
    public static Path fileFor(Path resultsDir, String language, String experiment) {
        return resultsDir.resolve(language + "_" + experiment + ".csv");
    }

    /** Replaces the file with the header + these rows. */
    public static void write(Path file, List<BenchmarkRow> rows) {
        Objects.requireNonNull(rows, "rows");
        StringBuilder csv = new StringBuilder(BenchmarkRow.HEADER).append('\n');
        for (BenchmarkRow row : rows) {
            csv.append(row.toCsvLine()).append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(tmp, csv, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, ATOMIC_MOVE, REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // what matters is the original error
            }
            throw new UncheckedIOException("No se pudo escribir " + file, e);
        }
    }

    /** Reads a file written by write, checking the header. */
    public static List<BenchmarkRow> read(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).equals(BenchmarkRow.HEADER)) {
                throw new IllegalArgumentException(file + " no tiene la cabecera del SPEC");
            }
            List<BenchmarkRow> rows = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                if (!line.isBlank()) {
                    rows.add(BenchmarkRow.parse(line));
                }
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + file, e);
        }
    }
}