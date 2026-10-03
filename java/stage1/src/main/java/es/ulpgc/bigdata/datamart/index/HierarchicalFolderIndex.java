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
 * Inverted index with one file per term (shared/SPEC.md, section 6):
 *
 *   <root>/B/boat.txt      10
 *                          20
 *   <root>/1/1813.txt      84      (terms starting with a digit: folder of that digit)
 *
 * Folder = first character of the term in uppercase; digits do not change.
 * Each file: one id per line, sorted and without repetitions.
 *
 * addDocument ACCUMULATES in memory; flush writes only the files of the terms
 * that have changed, each one with temp file + move. postings reads only the file
 * of the requested term and adds what is pending.
 */
public class HierarchicalFolderIndex implements InvertedIndex {

    /** What the Tokenizer produces: only a-z and 0-9. */
    private static final Pattern VALID_TERM = Pattern.compile("[a-z0-9]+");

    private static final String EXTENSION = ".txt";
    private static final String TMP_SUFFIX = ".tmp";

    private final Path root;

    /** Ids added since the last flush, per term. */
    private final Map<String, SortedSet<Integer>> pending = new HashMap<>();

    public HierarchicalFolderIndex(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    @Override
    public String name() {
        return "hierarchical";
    }

    // ------------------------------------------------------------------
    // Where each term lives
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
    // addDocument and postings
    // ------------------------------------------------------------------

    /** Only in memory: does not touch the disk until flush. */
    @Override
    public void addDocument(int bookId, Set<String> terms) {
        if (bookId < 0) {
            throw new IllegalArgumentException("book_id negativo: " + bookId);
        }
        Objects.requireNonNull(terms, "terms");
        terms.forEach(HierarchicalFolderIndex::requireValidTerm);   // all valid before touching anything
        for (String term : terms) {
            pending.computeIfAbsent(term, t -> new TreeSet<>()).add(bookId);
        }
    }

    /** Reads ONLY this term's file and adds what has not been saved yet. */
    @Override
    public List<Integer> postings(String term) {
        if (!isValidTerm(term)) {
            return List.of();                 // "Boat" is not a term (and on Windows/Mac it would find boat.txt)
        }
        SortedSet<Integer> ids = readIds(termFile(term));
        SortedSet<Integer> notYetSaved = pending.get(term);
        if (notYetSaved != null) {
            ids.addAll(notYetSaved);
        }
        return List.copyOf(ids);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * For each term with changes: read its file, merge the new ids and, if something
     * changed, rewrite ONLY that file (temp file + move). The rest is not touched.
     */
    @Override
    public void flush() {
        for (Map.Entry<String, SortedSet<Integer>> entry : pending.entrySet()) {
            Path file = termFile(entry.getKey());
            SortedSet<Integer> ids = readIds(file);
            if (ids.addAll(entry.getValue())) {          // false: all the ids were already there
                writeIds(file, ids);
            }
        }
        pending.clear();
    }

    /** Deletes pending memory and the whole index folder. */
    @Override
    public void clear() {
        pending.clear();
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> all = Files.walk(root)) {
            List<Path> paths = all.sorted(Comparator.reverseOrder()).toList();   // children before parents
            for (Path p : paths) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo borrar el índice " + root, e);
        }
    }

    /** Sum of the size of all the term files. */
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

    /** Nothing open between calls. It does not flush: whoever uses the index decides that. */
    @Override
    public void close() {
    }

    // ------------------------------------------------------------------
    // Reading and writing a term file
    // ------------------------------------------------------------------

    /** Ids of the file, sorted and without repetitions; empty if the file does not exist. */
    private static SortedSet<Integer> readIds(Path file) {
        SortedSet<Integer> ids = new TreeSet<>();
        if (!Files.exists(file)) {
            return ids;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) {                  // tolerates the trailing line break
                    ids.add(Integer.parseInt(trimmed));
                }
            }
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + file, e);
        } catch (NumberFormatException e) {
            // Same as the JSON: a corrupt file is not treated as empty,
            // because the next flush would overwrite it and its ids would be lost.
            throw new UncheckedIOException(new IOException("Fichero de término corrupto: " + file, e));
        }
    }

    /** One id per line, with temp file + move (challenge 11). */
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
                // what matters is the original error
            }
            throw new UncheckedIOException("No se pudo escribir " + file, e);
        }
    }

    // --- Outside the contract: useful for tests ---

    /** All the term files on disk (for tests and experiments). */
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