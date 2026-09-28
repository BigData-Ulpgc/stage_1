package es.ulpgc.bigdata.datamart.index;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Índice invertido en memoria: término -> conjunto ordenado de book_id.
 *
 *   boat  -> {10, 20}
 *   red   -> {10, 30}
 *
 * Recibe términos ya tokenizados (Tokenizer.uniqueTerms), no texto: así el
 * índice no depende del tokenizador y el benchmark puede medirlos por separado.
 */
public class InMemoryInvertedIndex {

    /**
     * Nivel 1 (HashMap): encontrar el contenedor de un término, en tiempo constante.
     * Nivel 2 (TreeSet): el contenedor guarda los ids sin repetir y siempre ordenados.
     */
    private final Map<String, SortedSet<Integer>> index = new HashMap<>();

    /** Añade el libro a la posting list de cada uno de sus términos. */
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

    /**
     * Ids de los libros que contienen el término, ordenados y sin repetir.
     * El término debe venir ya tokenizado: "Boat" no encuentra nada, "boat" sí.
     */
    public List<Integer> postings(String term) {
        SortedSet<Integer> ids = index.get(term);
        return ids == null ? List.of() : List.copyOf(ids);    // copia: nadie puede tocar el índice desde fuera
    }

    /** Todos los términos del índice, en orden alfabético. */
    public SortedSet<String> terms() {
        return new TreeSet<>(index.keySet());
    }

    /** Número de términos distintos. */
    public int termCount() {
        return index.size();
    }
}