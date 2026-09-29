package es.ulpgc.bigdata.datamart.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Índice invertido con un fichero por término (shared/SPEC.md, sección 6):
 *
 *   <root>/B/boat.txt      10
 *                          20
 *   <root>/1/1813.txt      84      (términos que empiezan por dígito: carpeta del dígito)
 *
 * Carpeta = primer carácter del término en mayúscula; los dígitos no cambian.
 * Cada fichero: un id por línea, ordenados y sin repetir.
 *
 * addDocument ACUMULA en memoria; flush escribe sólo los ficheros de los términos
 * que han cambiado, cada uno con temporal + mover. postings lee sólo el fichero
 * del término pedido y le suma lo pendiente.
 */
public class HierarchicalFolderIndex implements InvertedIndex {

    /** Lo que produce el Tokenizer: sólo a-z y 0-9. */
    private static final Pattern VALID_TERM = Pattern.compile("[a-z0-9]+");

    private static final String EXTENSION = ".txt";
    private static final String TMP_SUFFIX = ".tmp";

    private final Path root;

    /** Ids añadidos desde el último flush, por término. */
    private final Map<String, SortedSet<Integer>> pending = new HashMap<>();

    public HierarchicalFolderIndex(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    @Override
    public String name() {
        return "hierarchical";
    }

    // ------------------------------------------------------------------
    // Dónde vive cada término
    // ------------------------------------------------------------------

    /** "adventure" -> <root>/A/adventure.txt ; "1813" -> <root>/1/1813.txt */
    Path termFile(String term) {
        requireValidTerm(term);
        String folder = term.substring(0, 1).toUpperCase(Locale.ROOT);
        return root.resolve(folder).resolve(term + EXTENSION);
    }

    private static boolean isValidTerm(String term) {
        return term != null && VALID_TERM.matcher(term).matches();
    }

    private static void requireValidTerm(String term) {
        if (!isValidTerm(term)) {
            throw new IllegalArgumentException("Término no válido para el índice: \"" + term + "\"");
        }
    }

    // ------------------------------------------------------------------
    // addDocument y postings
    // ------------------------------------------------------------------

    /** Sólo en memoria: no toca el disco hasta flush. */
    @Override
    public void addDocument(int bookId, Set<String> terms) {
        if (bookId < 0) {
            throw new IllegalArgumentException("book_id negativo: " + bookId);
        }
        Objects.requireNonNull(terms, "terms");
        terms.forEach(HierarchicalFolderIndex::requireValidTerm);   // todos válidos antes de tocar nada
        for (String term : terms) {
            pending.computeIfAbsent(term, t -> new TreeSet<>()).add(bookId);
        }
    }

    /** Lee SÓLO el fichero de este término y le suma lo que aún no se ha guardado. */
    @Override
    public List<Integer> postings(String term) {
        if (!isValidTerm(term)) {
            return List.of();                 // "Boat" no es un término (y en Windows/Mac encontraría boat.txt)
        }
        SortedSet<Integer> ids = readIds(termFile(term));
        SortedSet<Integer> notYetSaved = pending.get(term);
        if (notYetSaved != null) {
            ids.addAll(notYetSaved);
        }
        return List.copyOf(ids);
    }

    // ------------------------------------------------------------------
    // Persistencia
    // ------------------------------------------------------------------

    /**
     * Por cada término con cambios: leer su fichero, unir los ids nuevos y, si algo
     * cambió, reescribir SÓLO ese fichero (temporal + mover). El resto no se toca.
     */
    @Override
    public void flush() {
        for (Map.Entry<String, SortedSet<Integer>> entry : pending.entrySet()) {
            Path file = termFile(entry.getKey());
            SortedSet<Integer> ids = readIds(file);
            if (ids.addAll(entry.getValue())) {          // false: todos los ids ya estaban
                writeIds(file, ids);
            }
        }
        pending.clear();
    }

    /** Borra memoria pendiente y la carpeta entera del índice. */
    @Override
    public void clear() {
        pending.clear();
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> all = Files.walk(root)) {
            List<Path> paths = all.sorted(Comparator.reverseOrder()).toList();   // hijos antes que padres
            for (Path p : paths) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo borrar el índice " + root, e);
        }
    }

    /** Suma del tamaño de todos los ficheros de términos. */
    @Override
    public long diskUsageBytes() {
        if (!Files.exists(root)) {
            return 0;
        }
        try (Stream<Path> all = Files.walk(root)) {
            long total = 0;
            for (Path p : (Iterable<Path>) all::iterator) {
                if (Files.isRegularFile(p)) {
                    total += Files.size(p);
                }
            }
            return total;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo medir " + root, e);
        }
    }

    /** Nada abierto entre llamadas. No hace flush: lo decide quien usa el índice. */
    @Override
    public void close() {
    }

    // ------------------------------------------------------------------
    // Lectura y escritura de un fichero de término
    // ------------------------------------------------------------------

    /** Ids del fichero, ordenados y sin repetir; vacío si el fichero no existe. */
    private static SortedSet<Integer> readIds(Path file) {
        SortedSet<Integer> ids = new TreeSet<>();
        if (!Files.exists(file)) {
            return ids;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) {                  // tolera el salto de línea final
                    ids.add(Integer.parseInt(trimmed));
                }
            }
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + file, e);
        } catch (NumberFormatException e) {
            // Igual que el JSON: un fichero corrupto no se trata como vacío,
            // porque el siguiente flush lo sobrescribiría y se perderían sus ids.
            throw new UncheckedIOException(new IOException("Fichero de término corrupto: " + file, e));
        }
    }

    /** Un id por línea, con temporal + mover (reto 11). */
    private static void writeIds(Path file, SortedSet<Integer> ids) {
        Path tmp = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
        StringBuilder content = new StringBuilder();
        for (int id : ids) {
            content.append(id).append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, ATOMIC_MOVE, REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // lo importante es el error original
            }
            throw new UncheckedIOException("No se pudo escribir " + file, e);
        }
    }

    // --- Fuera del contrato: útil para tests ---

    /** Todos los ficheros de términos en disco (para tests y experimentos). */
    List<Path> termFiles() {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> all = Files.walk(root)) {
            List<Path> files = new ArrayList<>();
            all.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(EXTENSION)).forEach(files::add);
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo listar " + root, e);
        }
    }
}