package es.ulpgc.bigdata.datamart.index;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Índice invertido sólo en memoria: término -> conjunto ordenado de book_id.
 *
 *   boat  -> {10, 20}
 *   red   -> {10, 30}
 *
 * No escribe nada en disco: flush no hace nada y los datos se pierden al cerrar.
 * Sirve para tests y como referencia de "índice perfecto" en el benchmark.
 */
public class InMemoryInvertedIndex implements InvertedIndex {

    /**
     * Nivel 1 (HashMap): encontrar el contenedor de un término, en tiempo constante.
     * Nivel 2 (TreeSet): el contenedor guarda los ids sin repetir y siempre ordenados.
     */
    private final Map<String, SortedSet<Integer>> index = new HashMap<>();

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public void addDocument(int bookId, Set<String> terms) {
        if (bookId < 0) {
            throw new IllegalArgumentException("book_id negativo: " + bookId);
        }
        Objects.requireNonNull(terms, "terms");
        for (String term : terms) {
            index.computeIfAbsent(term, t -> new TreeSet<>())   // 1. encontrar o crear el contenedor
                 .add(bookId);                                   // 2. meter el id (si ya está, no hace nada)
        }
    }

    @Override
    public List<Integer> postings(String term) {
        SortedSet<Integer> ids = index.get(term);
        return ids == null ? List.of() : List.copyOf(ids);    // copia: nadie puede tocar el índice desde fuera
    }

    /** En memoria no hay nada que persistir. */
    @Override
    public void flush() {
    }

    @Override
    public void clear() {
        index.clear();
    }

    /** No usa disco. */
    @Override
    public long diskUsageBytes() {
        return 0;
    }

    /** No tiene ficheros ni conexiones que liberar. */
    @Override
    public void close() {
    }

    // --- Métodos propios, fuera del contrato (útiles para tests y depuración) ---

    /** Todos los términos del índice, en orden alfabético. */
    public SortedSet<String> terms() {
        return new TreeSet<>(index.keySet());
    }

    /** Número de términos distintos. */
    public int termCount() {
        return index.size();
    }
}