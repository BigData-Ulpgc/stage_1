package es.ulpgc.bigdata.datamart.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Inverted index stored in ONE single JSON file (shared/SPEC.md, section 6):
 *
 *   {"blue":[20],"boat":[10,20],"fast":[20],"island":[30],"red":[10,30],"sails":[10,20]}
 *
 * The whole index lives in memory. When the object is created the JSON is loaded if it exists;
 * flush rewrites it WHOLE (temp file + move); clear deletes memory and file.
 */
public class MonolithicJsonIndex implements InvertedIndex {

    /** Type of what is read from the JSON: sorted terms -> sorted ids without repetitions. */
    private static final TypeReference<TreeMap<String, TreeSet<Integer>>> INDEX_TYPE = new TypeReference<>() {};

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Path tmpFile;
    private final TreeMap<String, TreeSet<Integer>> index;

    /** true if there are changes in memory that are not in the file yet. */
    private boolean dirty = false;

    public MonolithicJsonIndex(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        this.tmpFile = file.resolveSibling(file.getFileName() + ".tmp");
        this.index = load(file);
    }

    /** If the JSON exists, reads it whole; otherwise, starts empty. */
    private static TreeMap<String, TreeSet<Integer>> load(Path file) {
        if (!Files.exists(file)) {
            return new TreeMap<>();
        }
        try {
            TreeMap<String, TreeSet<Integer>> loaded = MAPPER.readValue(file.toFile(), INDEX_TYPE);
            return loaded == null ? new TreeMap<>() : loaded;
        } catch (IOException e) {
            // An empty index is not returned: the next flush would delete the good index.
            throw new UncheckedIOException("No se pudo leer el índice " + file, e);
        }
    }

    @Override
    public String name() {
        return "monolithic";
    }

    // ------------------------------------------------------------------
    // In-memory operations (same as InMemoryInvertedIndex)
    // ------------------------------------------------------------------

    @Override
    public void addDocument(int bookId, Set<String> terms) {
        if (bookId < 0) {
            throw new IllegalArgumentException("book_id negativo: " + bookId);
        }
        Objects.requireNonNull(terms, "terms");
        for (String term : terms) {
            if (index.computeIfAbsent(term, t -> new TreeSet<>()).add(bookId)) {
                dirty = true;                                 // only if the id was new
            }
        }
    }

    @Override
    public List<Integer> postings(String term) {
        TreeSet<Integer> ids = index.get(term);
        return ids == null ? List.of() : List.copyOf(ids);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Rewrites the WHOLE JSON: even if only one term has changed, all of them are written.
     * First to inverted_index.json.tmp and then it is renamed (as in challenge 11), so
     * that a crash halfway never leaves a half-written JSON with the good name.
     */
    @Override
    public void flush() {
        if (!dirty) {
            return;                                           // nothing new: the disk is not touched
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(tmpFile)) {
                MAPPER.writeValue(out, index);                // written straight to the file
            }
            try {
                Files.move(tmpFile, file, ATOMIC_MOVE, REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpFile, file, REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmpFile);
            } catch (IOException ignored) {
                // what matters is the original error
            }
            throw new UncheckedIOException("No se pudo guardar el índice " + file, e);
        }
    }

    @Override
    public void clear() {
        index.clear();
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(tmpFile);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo borrar el índice " + file, e);
        }
        dirty = false;
    }

    @Override
    public long diskUsageBytes() {
        try {
            return Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo medir " + file, e);
        }
    }

    /** Nothing is open between calls. It does not flush: whoever uses the index decides that. */
    @Override
    public void close() {
    }

    // --- Outside the contract: useful for tests and debugging ---

    /** Number of distinct terms. */
    public int termCount() {
        return index.size();
    }
}