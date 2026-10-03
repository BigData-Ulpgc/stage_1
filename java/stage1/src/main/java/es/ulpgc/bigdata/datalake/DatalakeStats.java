package es.ulpgc.bigdata.datalake;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Snapshot of how a datalake occupies the file system.
 *
 * @param directories     folders under the root (not counting the root)
 * @param files           regular files
 * @param bytes           sum of the logical size of the files
 * @param maxEntriesInDir entries of the most populated directory (root included)
 */
public record DatalakeStats(long directories, long files, long bytes, long maxEntriesInDir) {

    public static DatalakeStats of(Path root) {
        if (!Files.isDirectory(root)) {
            return new DatalakeStats(0, 0, 0, 0);
        }
        long directories = 0;
        long files = 0;
        long bytes = 0;
        Map<Path, Long> entriesPerDir = new HashMap<>();

        // Here we DO want Files.walk: the goal is to count absolutely everything.
        try (Stream<Path> all = Files.walk(root)) {
            for (Path p : (Iterable<Path>) all::iterator) {
                if (p.equals(root)) {
                    continue;
                }
                entriesPerDir.merge(p.getParent(), 1L, Long::sum);
                if (Files.isDirectory(p)) {
                    directories++;
                } else if (Files.isRegularFile(p)) {
                    files++;
                    bytes += Files.size(p);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo recorrer " + root, e);
        }
        long maxEntries = entriesPerDir.values().stream().mapToLong(Long::longValue).max().orElse(0);
        return new DatalakeStats(directories, files, bytes, maxEntries);
    }
}