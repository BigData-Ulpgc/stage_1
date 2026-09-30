package es.ulpgc.bigdata.datalake;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Foto de cómo ocupa un datalake el sistema de ficheros.
 *
 * @param directories     carpetas bajo la raíz (sin contar la raíz)
 * @param files           ficheros regulares
 * @param bytes           suma del tamaño lógico de los ficheros
 * @param maxEntriesInDir entradas del directorio más poblado (incluida la raíz)
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

        // Aquí SÍ queremos Files.walk: el objetivo es contarlo absolutamente todo.
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