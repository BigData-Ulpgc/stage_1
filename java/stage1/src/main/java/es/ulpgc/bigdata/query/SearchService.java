package es.ulpgc.bigdata.query;

import es.ulpgc.bigdata.datamart.index.InvertedIndex;
import es.ulpgc.bigdata.datamart.index.Tokenizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Buscador AND (shared/SPEC.md, sección 7):
 *
 *   consulta -> tokens -> posting lists -> intersección
 *
 * Nunca lee libros: sólo usa el Tokenizer y el InvertedIndex, sea cual sea
 * su organización física (memoria, JSON, carpetas o Mongo).
 */
public class SearchService {

    private final Tokenizer tokenizer;
    private final InvertedIndex index;

    public SearchService(Tokenizer tokenizer, InvertedIndex index) {
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.index = Objects.requireNonNull(index, "index");
    }

    /**
     * Ids de los libros que contienen TODOS los términos de la consulta,
     * ordenados de menor a mayor. Si la consulta no tiene ningún término
     * válido (vacía, sólo stopwords o palabras de 1 letra), devuelve [].
     */
    public List<Integer> search(String query) {
        Objects.requireNonNull(query, "query");

        // 1. Mismo tokenizador que al indexar. uniqueTerms ya quita repetidos
        //    y conserva el orden de primera aparición.
        Set<String> terms = tokenizer.uniqueTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        // 2. Posting list de cada término. Si una está vacía, el AND ya es vacío:
        //    no hace falta ni pedir las demás (en Mongo o en disco, cada una cuesta).
        List<List<Integer>> postingLists = new ArrayList<>();
        for (String term : terms) {
            List<Integer> postings = index.postings(term);
            if (postings.isEmpty()) {
                return List.of();
            }
            postingLists.add(postings);
        }

        // 3. Primero las listas más cortas: el resultado nunca puede ser más
        //    largo que la lista más corta, así que cada intersección trabaja menos.
        postingLists.sort(Comparator.comparingInt(List::size));

        // 4. Intersecciones encadenadas, cortando en cuanto el resultado queda vacío.
        List<Integer> result = postingLists.get(0);
        for (int i = 1; i < postingLists.size() && !result.isEmpty(); i++) {
            result = intersect(result, postingLists.get(i));
        }
        return List.copyOf(result);
    }

    /**
     * Intersección de dos listas ORDENADAS y sin repetidos, con dos punteros.
     * Cada paso avanza al menos un puntero, así que hace como mucho n + m pasos.
     */
    static List<Integer> intersect(List<Integer> a, List<Integer> b) {
        List<Integer> result = new ArrayList<>(Math.min(a.size(), b.size()));
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            int x = a.get(i);
            int y = b.get(j);
            if (x == y) {          // está en las dos: se guarda y avanzan ambos
                result.add(x);
                i++;
                j++;
            } else if (x < y) {    // x no puede estar en b (todo lo que queda en b es mayor)
                i++;
            } else {               // y no puede estar en a
                j++;
            }
        }
        return result;
    }
}