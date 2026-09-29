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
 * Índice invertido guardado en UN solo fichero JSON (shared/SPEC.md, sección 6):
 *
 *   {"blue":[20],"boat":[10,20],"fast":[20],"island":[30],"red":[10,30],"sails":[10,20]}
 *
 * Todo el índice vive en memoria. Al crear el objeto se carga el JSON si existe;
 * flush lo reescribe ENTERO (temporal + mover); clear borra memoria y fichero.
 */
public class MonolithicJsonIndex implements InvertedIndex {

    /** Tipo de lo que se lee del JSON: términos ordenados -> ids ordenados y sin repetir. */
    private static final TypeReference<TreeMap<String, TreeSet<Integer>>> INDEX_TYPE = new TypeReference<>() {};

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Path tmpFile;
    private final TreeMap<String, TreeSet<Integer>> index;

    /** true si hay cambios en memoria que aún no están en el fichero. */
    private boolean dirty = false;

    public MonolithicJsonIndex(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        this.tmpFile = file.resolveSibling(file.getFileName() + ".tmp");
        this.index = load(file);
    }

    /** Si el JSON existe, lo lee entero; si no, empieza vacío. */
    private static TreeMap<String, TreeSet<Integer>> load(Path file) {
        if (!Files.exists(file)) {
            return new TreeMap<>();
        }
        try {
            TreeMap<String, TreeSet<Integer>> loaded = MAPPER.readValue(file.toFile(), INDEX_TYPE);
            return loaded == null ? new TreeMap<>() : loaded;
        } catch (IOException e) {
            // No se devuelve un índice vacío: el siguiente flush borraría el índice bueno.
            throw new UncheckedIOException("No se pudo leer el índice " + file, e);
        }
    }

    @Override
    public String name() {
        return "monolithic";
    }

    // ------------------------------------------------------------------
    // Operaciones en memoria (iguales que InMemoryInvertedIndex)
    // ------------------------------------------------------------------

    @Override
    public void addDocument(int bookId, Set<String> terms) {
        if (bookId < 0) {
            throw new IllegalArgumentException("book_id negativo: " + bookId);
        }
        Objects.requireNonNull(terms, "terms");
        for (String term : terms) {
            if (index.computeIfAbsent(term, t -> new TreeSet<>()).add(bookId)) {
                dirty = true;                                 // sólo si el id era nuevo
            }
        }
    }

    @Override
    public List<Integer> postings(String term) {
        TreeSet<Integer> ids = index.get(term);
        return ids == null ? List.of() : List.copyOf(ids);
    }

    // ------------------------------------------------------------------
    // Persistencia
    // ------------------------------------------------------------------

    /**
     * Reescribe el JSON COMPLETO: aunque sólo haya cambiado un término, se escriben todos.
     * Primero a inverted_index.json.tmp y después se renombra (como en el reto 11), para
     * que un corte a mitad nunca deje un JSON a medias con el nombre bueno.
     */
    @Override
    public void flush() {
        if (!dirty) {
            return;                                           // nada nuevo: no se toca el disco
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(tmpFile)) {
                MAPPER.writeValue(out, index);                // se escribe directo al fichero
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
                // lo importante es el error original
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

    /** No hay nada abierto entre llamadas. No hace flush: eso lo decide quien usa el índice. */
    @Override
    public void close() {
    }

    // --- Fuera del contrato: útil para tests y depuración ---

    /** Número de términos distintos. */
    public int termCount() {
        return index.size();
    }
}